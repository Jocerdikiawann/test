package com.example.service;

import com.example.util.ImagePositioningHelper;
import com.example.util.HeaderFooterReplacementHelper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.docx4j.Docx4J;
import org.docx4j.XmlUtils;
import org.docx4j.convert.in.xhtml.XHTMLImporterImpl;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.wml.*;
import org.docx4j.jaxb.Context;

import javax.xml.bind.JAXBElement;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class DocxTemplateService {

    private static final Pattern HTML_PLACEHOLDER_PATTERN = Pattern.compile("<<HTML>>");

    /**
     * Load docx dari GCS/WebDAV dan replace placeholders
     * Khusus placeholder <<HTML>> akan di-convert dari HTML string ke docx elements
     */
    public Uni<byte[]> generateDocxFromTemplate(
            InputStream templateStream,
            Map<String, String> placeholders
    ) {
        return Uni.createFrom().item(() -> {
            try {
                // Load template docx
                WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage.load(templateStream);
                MainDocumentPart mainDocumentPart = wordMLPackage.getMainDocumentPart();

                // Pisahkan HTML placeholder dari placeholders biasa
                String htmlContent = placeholders.get("<<HTML>>");

                // Replace placeholders TEXT biasa dulu (kecuali <<HTML>>)
                Map<String, String> textPlaceholders = new java.util.HashMap<>(placeholders);
                textPlaceholders.remove("<<HTML>>"); // Remove HTML placeholder

                replacePlaceholders(wordMLPackage, textPlaceholders);

                // Replace HTML placeholder dengan generated docx elements
                if (htmlContent != null && !htmlContent.isEmpty()) {
                    replaceHtmlPlaceholder(wordMLPackage, htmlContent);
                }

                // CRITICAL: Cleanup document sebelum save untuk fix JAXB validation errors
                DocxCleanupService cleanupService = new DocxCleanupService();
                cleanupService.cleanupDocument(wordMLPackage);
                cleanupService.removeExcessiveTabs(wordMLPackage);

                // Export ke byte array
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                Docx4J.save(wordMLPackage, outputStream);

                return outputStream.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException("Error generating docx: " + e.getMessage(), e);
            }
        });
    }

    /**
     * DEBUG MODE: Generate dengan logging lengkap
     */
    public Uni<byte[]> generateDocxFromTemplateDebug(
            InputStream templateStream,
            Map<String, String> placeholders
    ) {
        return Uni.createFrom().item(() -> {
            try {
                System.out.println("=== DOCX GENERATION DEBUG MODE ===\n");

                // Load template docx
                WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage.load(templateStream);

                // Debug BEFORE
                System.out.println("BEFORE REPLACEMENT:");
                HeaderFooterReplacementHelper.debugHeaderFooterStructure(wordMLPackage);

                MainDocumentPart mainDocumentPart = wordMLPackage.getMainDocumentPart();

                // Pisahkan HTML placeholder dari placeholders biasa
                String htmlContent = placeholders.get("<<HTML>>");

                // Replace placeholders TEXT biasa dulu (kecuali <<HTML>>)
                Map<String, String> textPlaceholders = new java.util.HashMap<>(placeholders);
                textPlaceholders.remove("<<HTML>>"); // Remove HTML placeholder

                System.out.println("\nREPLACING PLACEHOLDERS:");
                textPlaceholders.forEach((key, value) ->
                    System.out.println("  " + key + " => " +
                        (value.length() > 30 ? value.substring(0, 30) + "..." : value))
                );

                replacePlaceholders(wordMLPackage, textPlaceholders);

                // Replace HTML placeholder dengan generated docx elements
                if (htmlContent != null && !htmlContent.isEmpty()) {
                    System.out.println("\nREPLACING HTML PLACEHOLDER:");
                    System.out.println("  HTML length: " + htmlContent.length() + " chars");
                    replaceHtmlPlaceholder(wordMLPackage, htmlContent);
                }

                // Debug AFTER replacement
                System.out.println("\n\nAFTER REPLACEMENT:");
                HeaderFooterReplacementHelper.debugHeaderFooterStructure(wordMLPackage);

                // Validate before cleanup
                System.out.println("\n\nVALIDATING DOCUMENT:");
                DocxCleanupService cleanupService = new DocxCleanupService();
                List<String> issues = cleanupService.validateDocument(wordMLPackage);

                if (!issues.isEmpty()) {
                    System.out.println("Found " + issues.size() + " issues:");
                    issues.forEach(issue -> System.out.println("  - " + issue));
                } else {
                    System.out.println("✓ No validation issues found");
                }

                // Cleanup
                System.out.println("\nCLEANING UP DOCUMENT...");
                cleanupService.cleanupDocument(wordMLPackage);
                cleanupService.removeExcessiveTabs(wordMLPackage);

                // Validate after cleanup
                List<String> remainingIssues = cleanupService.validateDocument(wordMLPackage);
                if (!remainingIssues.isEmpty()) {
                    System.out.println("⚠ Remaining issues: " + remainingIssues.size());
                    remainingIssues.forEach(issue -> System.out.println("  - " + issue));
                } else {
                    System.out.println("✓ All issues cleaned up");
                }

                // Export ke byte array
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                Docx4J.save(wordMLPackage, outputStream);

                System.out.println("\n✓ Generation complete!");

                return outputStream.toByteArray();
            } catch (Exception e) {
                System.err.println("✗ Error: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Error generating docx: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Replace placeholders TEXT (bukan HTML) dengan mempertahankan formatting
     * Menggunakan approach yang lebih reliable via RelationshipsPart
     */
    private void replacePlaceholders(WordprocessingMLPackage wordMLPackage, Map<String, String> placeholders) throws Exception {
        MainDocumentPart mainDocumentPart = wordMLPackage.getMainDocumentPart();

        // 1. Replace di main document body
        replaceInPart(mainDocumentPart, placeholders);

        // 2. Replace di ALL headers (via RelationshipsPart - more reliable)
        List<HeaderPart> headerParts = HeaderFooterReplacementHelper.getAllHeaderParts(wordMLPackage);
        for (HeaderPart headerPart : headerParts) {
            List<P> headerParagraphs = HeaderFooterReplacementHelper.getParagraphsFromPart(headerPart);

            for (P paragraph : headerParagraphs) {
                if (ImagePositioningHelper.containsImage(paragraph)) {
                    replacePlaceholderInParagraphWithImages(paragraph, placeholders);
                } else {
                    replacePlaceholderInParagraph(paragraph, placeholders);
                }
            }
        }

        // 3. Replace di ALL footers (via RelationshipsPart)
        List<FooterPart> footerParts = HeaderFooterReplacementHelper.getAllFooterParts(wordMLPackage);
        for (FooterPart footerPart : footerParts) {
            List<P> footerParagraphs = HeaderFooterReplacementHelper.getParagraphsFromPart(footerPart);

            for (P paragraph : footerParagraphs) {
                if (ImagePositioningHelper.containsImage(paragraph)) {
                    replacePlaceholderInParagraphWithImages(paragraph, placeholders);
                } else {
                    replacePlaceholderInParagraph(paragraph, placeholders);
                }
            }
        }
    }

    /**
     * Replace <<HTML>> placeholder dengan converted HTML content
     */
    private void replaceHtmlPlaceholder(WordprocessingMLPackage wordMLPackage, String htmlContent) throws Exception {
        MainDocumentPart mainDocumentPart = wordMLPackage.getMainDocumentPart();

        // Find paragraph yang mengandung <<HTML>>
        List<Object> paragraphs = getAllElementFromObject(mainDocumentPart, P.class);

        for (Object obj : paragraphs) {
            P paragraph = (P) obj;
            String paragraphText = extractTextFromParagraph(paragraph);

            if (paragraphText.contains("<<HTML>>")) {
                // Found the HTML placeholder paragraph
                replaceParagraphWithHtml(wordMLPackage, paragraph, htmlContent);
                break; // Assume hanya ada satu <<HTML>> placeholder
            }
        }
    }

    /**
     * Replace paragraph yang berisi <<HTML>> dengan HTML content yang sudah di-convert
     */
    private void replaceParagraphWithHtml(
            WordprocessingMLPackage wordMLPackage,
            P placeholder,
            String htmlContent
    ) throws Exception {
        // Get parent container
        Object parent = placeholder.getParent();
        if (!(parent instanceof ContentAccessor)) {
            return;
        }

        ContentAccessor parentContainer = (ContentAccessor) parent;
        List<Object> parentContent = parentContainer.getContent();

        // Find index of placeholder paragraph
        int placeholderIndex = parentContent.indexOf(placeholder);
        if (placeholderIndex < 0) {
            return;
        }

        // Convert HTML to docx elements
        XHTMLImporterImpl xhtmlImporter = new XHTMLImporterImpl(wordMLPackage);
        String wrappedHtml = wrapHtmlForImport(htmlContent);
        List<Object> htmlElements = xhtmlImporter.convert(wrappedHtml, null);

        // Remove placeholder paragraph
        parentContent.remove(placeholderIndex);

        // Insert HTML elements at the same position
        parentContent.addAll(placeholderIndex, htmlElements);
    }

    /**
     * Replace text dalam part dengan mempertahankan formatting
     */
    private void replaceInPart(Object part, Map<String, String> placeholders) throws Exception {
        // Get all paragraphs
        List<Object> paragraphs = getAllElementFromObject(part, P.class);

        for (Object obj : paragraphs) {
            P paragraph = (P) obj;

            // Check jika paragraph mengandung image
            if (ImagePositioningHelper.containsImage(paragraph)) {
                // Special handling untuk paragraph dengan images
                replacePlaceholderInParagraphWithImages(paragraph, placeholders);
            } else {
                // Standard replacement
                replacePlaceholderInParagraph(paragraph, placeholders);
            }
        }
    }

    /**
     * Replace placeholder dalam paragraph yang mengandung images
     * Ini preserve images, tabs, breaks, dan positioningnya
     */
    private void replacePlaceholderInParagraphWithImages(P paragraph, Map<String, String> placeholders) {
        // Extract text untuk check apakah ada placeholder
        String paragraphText = extractTextFromParagraph(paragraph);

        boolean needsReplacement = false;
        String replacedText = paragraphText;

        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            if (paragraphText.contains(entry.getKey())) {
                replacedText = replacedText.replace(
                    entry.getKey(),
                    entry.getValue() != null ? entry.getValue() : ""
                );
                needsReplacement = true;
            }
        }

        if (!needsReplacement) return;

        // Backup paragraph properties (CRITICAL untuk alignment)
        PPr originalPPr = paragraph.getPPr();

        // Get parent untuk replacement
        Object parent = paragraph.getParent();
        if (!(parent instanceof ContentAccessor)) return;

        ContentAccessor parentContainer = (ContentAccessor) parent;
        int index = parentContainer.getContent().indexOf(paragraph);
        if (index < 0) return;

        // Create new paragraph dengan text replaced tapi images preserved
        ObjectFactory factory = Context.getWmlObjectFactory();
        P newParagraph = factory.createP();

        // CRITICAL: Copy ALL paragraph properties (alignment, spacing, indentation, etc)
        if (originalPPr != null) {
            try {
                newParagraph.setPPr((PPr) XmlUtils.deepCopy(originalPPr));
            } catch (Exception e) {
                newParagraph.setPPr(originalPPr);
            }
        }

        // Separate text runs, image runs, tabs, and breaks
        List<R> textRuns = new ArrayList<>();
        List<R> imageRuns = new ArrayList<>();
        List<Object> tabsAndBreaks = new ArrayList<>();

        for (Object content : paragraph.getContent()) {
            if (content instanceof R) {
                R run = (R) content;
                boolean hasImage = false;
                boolean hasTabOrBreak = false;

                for (Object runContent : run.getContent()) {
                    if (runContent instanceof JAXBElement) {
                        Object value = ((JAXBElement<?>) runContent).getValue();
                        if (value instanceof org.docx4j.wml.Drawing ||
                            value instanceof org.docx4j.wml.Pict) {
                            hasImage = true;
                        } else if (value instanceof R.Tab || value instanceof Br) {
                            hasTabOrBreak = true;
                            tabsAndBreaks.add(runContent);
                        }
                    } else if (runContent instanceof R.Tab || runContent instanceof Br) {
                        hasTabOrBreak = true;
                        tabsAndBreaks.add(runContent);
                    }
                }

                if (hasImage) {
                    imageRuns.add(run);
                } else if (hasTabOrBreak) {
                    // Store tab/break runs separately
                } else {
                    textRuns.add(run);
                }
            }
        }

        // Create new text run dengan replaced text
        if (!textRuns.isEmpty() || !replacedText.isEmpty()) {
            R newTextRun = factory.createR();

            // Copy formatting dari text run pertama
            if (!textRuns.isEmpty()) {
                R firstTextRun = textRuns.get(0);
                if (firstTextRun.getRPr() != null) {
                    try {
                        newTextRun.setRPr((RPr) XmlUtils.deepCopy(firstTextRun.getRPr()));
                    } catch (Exception e) {
                        newTextRun.setRPr(firstTextRun.getRPr());
                    }
                }
            }

            // Set replaced text
            Text text = factory.createText();
            text.setValue(replacedText);
            text.setSpace("preserve");
            newTextRun.getContent().add(factory.createRT(text));

            newParagraph.getContent().add(newTextRun);
        }

        // Add back tabs and breaks
        if (!tabsAndBreaks.isEmpty()) {
            for (Object tabOrBreak : tabsAndBreaks) {
                R specialRun = factory.createR();

                // Try to get formatting from first text run
                if (!textRuns.isEmpty() && textRuns.get(0).getRPr() != null) {
                    try {
                        specialRun.setRPr((RPr) XmlUtils.deepCopy(textRuns.get(0).getRPr()));
                    } catch (Exception e) {
                        specialRun.setRPr(textRuns.get(0).getRPr());
                    }
                }

                specialRun.getContent().add(tabOrBreak);
                newParagraph.getContent().add(specialRun);
            }
        }

        // Add back image runs (dengan semua properties)
        for (R imageRun : imageRuns) {
            try {
                R clonedImageRun = (R) XmlUtils.deepCopy(imageRun);
                newParagraph.getContent().add(clonedImageRun);
            } catch (Exception e) {
                newParagraph.getContent().add(imageRun);
            }
        }

        // Replace paragraph
        parentContainer.getContent().remove(index);
        parentContainer.getContent().add(index, newParagraph);
    }

    /**
     * Replace placeholder dalam paragraph dengan mempertahankan style
     * PLUS: Preserve tabs, line breaks, dan special elements
     * CRITICAL: Do NOT add unintended line breaks in headings!
     */
    private void replacePlaceholderInParagraph(P paragraph, Map<String, String> placeholders) {
        // IMPORTANT: Backup paragraph properties (alignment, spacing, indentation, dll)
        PPr originalParaProps = paragraph.getPPr();

        // Check if this is a heading - headings need special care
        boolean isHeading = isHeadingParagraph(paragraph);

        // Get all content dalam paragraph
        List<Object> allContent = paragraph.getContent();
        StringBuilder fullText = new StringBuilder();
        List<R> textRuns = new ArrayList<>();
        List<Object> nonTextContent = new ArrayList<>(); // Tabs, breaks, images, drawings
        List<Object> existingBreaks = new ArrayList<>(); // ONLY preserve EXISTING breaks

        // Collect full text dari semua runs + preserve non-text content
        for (Object contentObj : allContent) {
            if (contentObj instanceof R) {
                R run = (R) contentObj;
                boolean hasText = false;
                boolean hasSpecialContent = false;

                for (Object runContent : run.getContent()) {
                    if (runContent instanceof JAXBElement) {
                        Object value = ((JAXBElement<?>) runContent).getValue();
                        if (value instanceof Text) {
                            fullText.append(((Text) value).getValue());
                            hasText = true;
                        } else if (value instanceof R.Tab) {
                            // Tab character - preserve position
                            // BUT: In headings with multiple items, tab is INTENDED
                            if (!isHeading) {
                                existingBreaks.add(runContent);
                            }
                            hasSpecialContent = true;
                        } else if (value instanceof Br) {
                            // Line break - ONLY preserve if it was ALREADY there
                            existingBreaks.add(runContent);
                            hasSpecialContent = true;
                        } else {
                            // Non-text content (images, drawings, etc)
                            nonTextContent.add(runContent);
                            hasSpecialContent = true;
                        }
                    } else if (runContent instanceof R.Tab) {
                        // Direct tab element
                        if (!isHeading) {
                            existingBreaks.add(runContent);
                        }
                        hasSpecialContent = true;
                    } else if (runContent instanceof Br) {
                        // Direct break element - preserve EXISTING breaks
                        existingBreaks.add(runContent);
                        hasSpecialContent = true;
                    } else {
                        // Other run content
                        if (!hasSpecialContent) {
                            nonTextContent.add(runContent);
                        }
                    }
                }

                if (hasText) {
                    textRuns.add(run);
                }
            } else {
                // Preserve non-run content (bookmarks, hyperlinks, etc)
                nonTextContent.add(contentObj);
            }
        }

        String text = fullText.toString();
        boolean needsReplacement = false;
        String replacedText = text;

        // Check dan replace placeholders
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            if (text.contains(entry.getKey())) {
                replacedText = replacedText.replace(
                    entry.getKey(),
                    entry.getValue() != null ? entry.getValue() : ""
                );
                needsReplacement = true;
            }
        }

        if (!needsReplacement) return;

        // Ambil formatting dari run pertama
        R firstRun = null;
        RPr originalFormatting = null;

        for (R run : textRuns) {
            firstRun = run;
            originalFormatting = run.getRPr();
            break;
        }

        if (firstRun == null) return;

        // Clear hanya text runs, preserve non-text content
        allContent.clear();

        // Buat run baru dengan text yang sudah diganti
        R newRun = Context.getWmlObjectFactory().createR();

        // Copy formatting dari run pertama
        if (originalFormatting != null) {
            try {
                newRun.setRPr((RPr) XmlUtils.deepCopy(originalFormatting));
            } catch (Exception e) {
                // Fallback: clone manually
                RPr clonedRPr = Context.getWmlObjectFactory().createRPr();
                if (originalFormatting.getRFonts() != null) {
                    clonedRPr.setRFonts(originalFormatting.getRFonts());
                }
                if (originalFormatting.getSz() != null) {
                    clonedRPr.setSz(originalFormatting.getSz());
                }
                if (originalFormatting.getB() != null) {
                    clonedRPr.setB(originalFormatting.getB());
                }
                if (originalFormatting.getI() != null) {
                    clonedRPr.setI(originalFormatting.getI());
                }
                if (originalFormatting.getColor() != null) {
                    clonedRPr.setColor(originalFormatting.getColor());
                }
                newRun.setRPr(clonedRPr);
            }
        }

        // Set text baru
        Text newText = Context.getWmlObjectFactory().createText();
        newText.setValue(replacedText);
        newText.setSpace("preserve"); // Preserve spaces

        JAXBElement<Text> textElement = Context.getWmlObjectFactory().createRT(newText);
        newRun.getContent().add(textElement);

        // Add run baru ke paragraph
        allContent.add(newRun);

        // CRITICAL: ONLY add back EXISTING breaks (don't create new ones!)
        // For headings: we want items on SAME line, so DON'T add breaks
        if (!isHeading && !existingBreaks.isEmpty()) {
            for (Object breakOrTab : existingBreaks) {
                R specialRun = Context.getWmlObjectFactory().createR();

                // Copy formatting
                if (originalFormatting != null) {
                    try {
                        specialRun.setRPr((RPr) XmlUtils.deepCopy(originalFormatting));
                    } catch (Exception e) {
                        specialRun.setRPr(originalFormatting);
                    }
                }

                specialRun.getContent().add(breakOrTab);
                allContent.add(specialRun);
            }
        }

        // Add back non-text content (images, drawings)
        allContent.addAll(nonTextContent);

        // CRITICAL: Restore paragraph properties (alignment, spacing, etc)
        if (originalParaProps != null) {
            try {
                paragraph.setPPr((PPr) XmlUtils.deepCopy(originalParaProps));
            } catch (Exception e) {
                // Fallback: set original directly
                paragraph.setPPr(originalParaProps);
            }
        }
    }

    /**
     * Generate DOCX with text AND image placeholders
     *
     * @param templateStream Template DOCX
     * @param textPlaceholders Text placeholders (<<name>> → value)
     * @param imagePlaceholders Image placeholders (<<E_SIGN_1>> → ImageData)
     */
    public Uni<byte[]> generateDocxWithImages(
            InputStream templateStream,
            Map<String, String> textPlaceholders,
            Map<String, ImagePlaceholderService.ImageData> imagePlaceholders
    ) {
        return Uni.createFrom().item(() -> {
            try {
                // Load template docx
                WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage.load(templateStream);

                // 1. Replace TEXT placeholders first
                String htmlContent = textPlaceholders.get("<<HTML>>");
                Map<String, String> cleanTextPlaceholders = new HashMap<>(textPlaceholders);
                cleanTextPlaceholders.remove("<<HTML>>");

                replacePlaceholders(wordMLPackage, cleanTextPlaceholders);

                // 2. Replace IMAGE placeholders
                if (imagePlaceholders != null && !imagePlaceholders.isEmpty()) {
                    ImagePlaceholderService imageService = new ImagePlaceholderService();
                    imageService.replaceWithImages(wordMLPackage, imagePlaceholders);
                }

                // 3. Replace HTML placeholder (if any)
                if (htmlContent != null && !htmlContent.isEmpty()) {
                    replaceHtmlPlaceholder(wordMLPackage, htmlContent);
                }

                // 4. Cleanup
                DocxCleanupService cleanupService = new DocxCleanupService();
                cleanupService.cleanupDocument(wordMLPackage);
                cleanupService.removeExcessiveTabs(wordMLPackage);

                // 5. Save
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                Docx4J.save(wordMLPackage, outputStream);

                return outputStream.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException("Error generating docx with images: " + e.getMessage(), e);
            }
        });
    }


    /**
     * Extract text dari paragraph
     */
    private String extractTextFromParagraph(P paragraph) {
        StringBuilder text = new StringBuilder();

        for (Object obj : paragraph.getContent()) {
            if (obj instanceof R) {
                R run = (R) obj;
                for (Object content : run.getContent()) {
                    if (content instanceof JAXBElement) {
                        Object value = ((JAXBElement<?>) content).getValue();
                        if (value instanceof Text) {
                            text.append(((Text) value).getValue());
                        }
                    }
                }
            }
        }

        return text.toString();
    }

    /**
     * Wrap HTML dengan proper structure untuk import
     */
    private String wrapHtmlForImport(String html) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>");
        sb.append("<html><head>");
        sb.append("<meta charset='UTF-8'/>");
        sb.append("<style>");
        sb.append("body { font-family: Arial, sans-serif; font-size: 11pt; }");
        sb.append("table { border-collapse: collapse; width: 100%; }");
        sb.append("th, td { border: 1px solid black; padding: 8px; text-align: left; }");
        sb.append("</style>");
        sb.append("</head><body>");
        sb.append(html);
        sb.append("</body></html>");

        return sb.toString();
    }

    /**
     * Generate DOCX dan langsung convert ke PDF
     * Recommended: Pakai external converter (LibreOffice/Gotenberg) untuk production
     */
    public Uni<byte[]> generatePdfFromTemplate(
            InputStream templateStream,
            Map<String, String> placeholders,
            PdfConversionMethod method
    ) {
        return generateDocxFromTemplate(templateStream, placeholders)
            .flatMap(docxBytes -> {
                try {
                    // Load DOCX yang sudah di-generate
                    WordprocessingMLPackage wordMLPackage = WordprocessingMLPackage.load(
                        new ByteArrayInputStream(docxBytes)
                    );

                    // Convert ke PDF sesuai method
                    PdfConversionService pdfService = new PdfConversionService();

                    return switch (method) {
                        case LIBREOFFICE -> pdfService.convertToPdfWithLibreOffice(docxBytes);
                        case GOTENBERG -> pdfService.convertToPdfWithGotenberg(
                            docxBytes,
                            "http://localhost:3000" // Configure via properties
                        );
                        case DOCX4J_IMPROVED -> pdfService.convertToPdfImproved(wordMLPackage);
                        case DOCX4J_FOP -> pdfService.convertToPdfWithFop(wordMLPackage);
                        default -> pdfService.convertToPdfViaSave(wordMLPackage);
                    };
                } catch (Exception e) {
                    throw new RuntimeException("Error in PDF generation: " + e.getMessage(), e);
                }
            });
    }

    /**
     * Enum untuk PDF conversion methods
     */
    public enum PdfConversionMethod {
        LIBREOFFICE,      // Best quality, requires LibreOffice installed
        GOTENBERG,        // Best for production, requires Docker container
        DOCX4J_IMPROVED,  // Improved docx4j internal converter
        DOCX4J_FOP,       // Apache FOP based
        DOCX4J_VIA_SAVE   // Save DOCX first, then convert
    }
