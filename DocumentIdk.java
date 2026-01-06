package com.company.service;

import io.quarkus.qute.Template;
import io.quarkus.qute.Location;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class PdfConverterService {

    @Inject
    @Location("document-template.html")
    Template documentTemplate;

    @ConfigProperty(name = "libreoffice.path", defaultValue = "soffice")
    String libreOfficePath;

    @ConfigProperty(name = "pdf.header.image.path")
    String headerImagePath;

    @ConfigProperty(name = "pdf.footer.image.path")
    String footerImagePath;

    /**
     * Main method untuk convert HTML ke PDF dengan header/footer image
     */
    public byte[] generatePdfWithHeaderFooter(Map<String, Object> templateData) throws Exception {
        // 1. Render HTML dari Qute template
        String htmlContent = documentTemplate.data(templateData).render();

        // 2. Convert HTML ke PDF pakai LibreOffice
        byte[] basePdf = convertHtmlToPdfWithLibreOffice(htmlContent);

        // 3. Add header/footer ke PDF
        byte[] finalPdf = addHeaderFooterToPdf(basePdf);

        return finalPdf;
    }

    /**
     * Convert HTML ke PDF menggunakan LibreOffice headless
     */
    private byte[] convertHtmlToPdfWithLibreOffice(String htmlContent) throws Exception {
        // Create temp directory
        Path tempDir = Files.createTempDirectory("pdf_conversion_");
        String sessionId = UUID.randomUUID().toString();

        try {
            // Save HTML to temp file
            Path htmlFile = tempDir.resolve("input.html");
            Files.writeString(htmlFile, htmlContent);

            // LibreOffice profile path (isolated per conversion)
            String profilePath = tempDir.resolve("lo_profile").toString();

            // Build LibreOffice command
            ProcessBuilder pb = new ProcessBuilder(
                libreOfficePath,
                "--headless",
                "--invisible",
                "--nodefault",
                "--nofirststartwizard",
                "--nolockcheck",
                "--nologo",
                "--norestore",
                "-env:UserInstallation=file://" + profilePath,
                "--convert-to", "pdf",
                "--outdir", tempDir.toString(),
                htmlFile.toString()
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read output for debugging
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("LibreOffice: " + line);
            }

            // Wait with timeout (30 seconds)
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("LibreOffice conversion timeout");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("LibreOffice conversion failed with exit code: " + exitCode);
            }

            // Read generated PDF
            Path pdfFile = tempDir.resolve("input.pdf");
            if (!Files.exists(pdfFile)) {
                throw new RuntimeException("PDF file not generated");
            }

            return Files.readAllBytes(pdfFile);

        } finally {
            // Cleanup temp directory
            deleteDirectory(tempDir.toFile());
        }
    }

    /**
     * Add header dan footer dengan image ke PDF
     */
    private byte[] addHeaderFooterToPdf(byte[] pdfBytes) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try (PDDocument document = PDDocument.load(pdfBytes)) {

            // Load header dan footer images
            byte[] headerImageBytes = Files.readAllBytes(Paths.get(headerImagePath));
            byte[] footerImageBytes = Files.readAllBytes(Paths.get(footerImagePath));

            PDImageXObject headerImage = PDImageXObject.createFromByteArray(
                document, headerImageBytes, "header"
            );
            PDImageXObject footerImage = PDImageXObject.createFromByteArray(
                document, footerImageBytes, "footer"
            );

            // Iterate semua pages
            int pageNumber = 1;
            int totalPages = document.getNumberOfPages();

            for (PDPage page : document.getPages()) {
                PDRectangle pageSize = page.getMediaBox();
                float pageWidth = pageSize.getWidth();
                float pageHeight = pageSize.getHeight();

                // Create content stream untuk page ini
                try (PDPageContentStream contentStream = new PDPageContentStream(
                    document, page,
                    PDPageContentStream.AppendMode.APPEND,
                    true, true
                )) {

                    // === HEADER ===
                    float headerHeight = 40;
                    float headerWidth = 120;
                    float headerX = (pageWidth - headerWidth) / 2; // Center
                    float headerY = pageHeight - headerHeight - 20; // 20pt dari atas

                    contentStream.drawImage(
                        headerImage,
                        headerX, headerY,
                        headerWidth, headerHeight
                    );

                    // === FOOTER ===
                    float footerHeight = 30;
                    float footerWidth = 100;
                    float footerX = (pageWidth - footerWidth) / 2; // Center
                    float footerY = 20; // 20pt dari bawah

                    contentStream.drawImage(
                        footerImage,
                        footerX, footerY,
                        footerWidth, footerHeight
                    );

                    // Optional: Add page number di footer
                    contentStream.beginText();
                    contentStream.setFont(
                        org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA,
                        10
                    );

                    String pageText = "Page " + pageNumber + " of " + totalPages;
                    float textWidth = org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA
                        .getStringWidth(pageText) / 1000 * 10;
                    float textX = (pageWidth - textWidth) / 2;
                    float textY = footerY - 15;

                    contentStream.newLineAtOffset(textX, textY);
                    contentStream.showText(pageText);
                    contentStream.endText();
                }

                pageNumber++;
            }

            document.save(outputStream);
        }

        return outputStream.toByteArray();
    }

    /**
     * Helper method untuk delete directory recursively
     */
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
}
