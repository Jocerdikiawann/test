package com.example.service;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.docx4j.dml.wordprocessingDrawing.Inline;
import org.docx4j.jaxb.Context;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.BinaryPartAbstractImage;
import org.docx4j.wml.*;

import javax.xml.bind.JAXBElement;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service untuk replace text placeholder dengan image
 * Support: byte[], InputStream, URL
 */
@ApplicationScoped
public class ImagePlaceholderService {

    /**
     * Replace text placeholders dengan images
     *
     * @param wordMLPackage Document package
     * @param imagePlaceholders Map of placeholder → ImageData
     */
    public void replaceWithImages(
            WordprocessingMLPackage wordMLPackage,
            Map<String, ImageData> imagePlaceholders
    ) throws Exception {

        for (Map.Entry<String, ImageData> entry : imagePlaceholders.entrySet()) {
            String placeholder = entry.getKey();
            ImageData imageData = entry.getValue();

            // Find and replace placeholder
            replacePlaceholderWithImage(
                wordMLPackage,
                placeholder,
                imageData
            );
        }
    }

    /**
     * Replace single placeholder dengan image
     */
    private void replacePlaceholderWithImage(
            WordprocessingMLPackage wordMLPackage,
            String placeholder,
            ImageData imageData
    ) throws Exception {

        // Get all paragraphs
        List<Object> paragraphs = getAllParagraphs(
            wordMLPackage.getMainDocumentPart()
        );

        for (Object obj : paragraphs) {
            P paragraph = (P) obj;
            String text = extractText(paragraph);

            if (text.contains(placeholder)) {
                // Replace this paragraph's text with image
                replaceParagraphTextWithImage(
                    wordMLPackage,
                    paragraph,
                    placeholder,
                    imageData
                );
            }
        }
    }

    /**
     * Replace text dalam paragraph dengan image
     */
    private void replaceParagraphTextWithImage(
            WordprocessingMLPackage wordMLPackage,
            P paragraph,
            String placeholder,
            ImageData imageData
    ) throws Exception {

        ObjectFactory factory = Context.getWmlObjectFactory();

        // Backup paragraph properties
        PPr originalPPr = paragraph.getPPr();

        // Get text before and after placeholder
        String fullText = extractText(paragraph);
        String[] parts = fullText.split(placeholder, 2);
        String textBefore = parts.length > 0 ? parts[0] : "";
        String textAfter = parts.length > 1 ? parts[1] : "";

        // Clear paragraph content
        paragraph.getContent().clear();

        // Add text before (if any)
        if (!textBefore.isEmpty()) {
            R textRun = factory.createR();
            Text text = factory.createText();
            text.setValue(textBefore);
            text.setSpace("preserve");
            textRun.getContent().add(factory.createRT(text));
            paragraph.getContent().add(textRun);
        }

        // Add image
        R imageRun = createImageRun(wordMLPackage, imageData);
        paragraph.getContent().add(imageRun);

        // Add text after (if any)
        if (!textAfter.isEmpty()) {
            R textRun = factory.createR();
            Text text = factory.createText();
            text.setValue(textAfter);
            text.setSpace("preserve");
            textRun.getContent().add(factory.createRT(text));
            paragraph.getContent().add(textRun);
        }

        // Restore paragraph properties
        if (originalPPr != null) {
            paragraph.setPPr(originalPPr);
        }
    }

    /**
     * Create run dengan image
     */
    private R createImageRun(
            WordprocessingMLPackage wordMLPackage,
            ImageData imageData
    ) throws Exception {

        ObjectFactory factory = Context.getWmlObjectFactory();

        // Get image bytes
        byte[] imageBytes = imageData.getBytes();

        // Add image to package
        BinaryPartAbstractImage imagePart = BinaryPartAbstractImage.createImagePart(
            wordMLPackage,
            imageBytes
        );

        // Calculate dimensions
        int imageWidth = imageData.getWidthEMU();
        int imageHeight = imageData.getHeightEMU();

        // Create inline image
        Inline inline = imagePart.createImageInline(
            imageData.getFilename(),
            imageData.getAltText(),
            0, // id
            1, // id
            false
        );

        // Set size
        inline.getExtent().setCx(imageWidth);
        inline.getExtent().setCy(imageHeight);

        // Create drawing
        Drawing drawing = factory.createDrawing();
        drawing.getAnchorOrInline().add(inline);

        // Create run
        R run = factory.createR();
        run.getContent().add(drawing);

        return run;
    }

    /**
     * Extract text dari paragraph
     */
    private String extractText(P paragraph) {
        StringBuilder text = new StringBuilder();

        for (Object content : paragraph.getContent()) {
            if (content instanceof R) {
                R run = (R) content;
                for (Object runContent : run.getContent()) {
                    if (runContent instanceof JAXBElement) {
                        Object value = ((JAXBElement<?>) runContent).getValue();
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
     * Get all paragraphs recursively
     */
    private List<Object> getAllParagraphs(Object obj) {
        List<Object> result = new java.util.ArrayList<>();

        if (obj instanceof JAXBElement) {
            obj = ((JAXBElement<?>) obj).getValue();
        }

        if (obj instanceof P) {
            result.add(obj);
        } else if (obj instanceof ContentAccessor) {
            List<?> children = ((ContentAccessor) obj).getContent();
            for (Object child : children) {
                result.addAll(getAllParagraphs(child));
            }
        }

        return result;
    }

    /**
     * Image data class
     */
    public static class ImageData {
        private byte[] bytes;
        private String filename;
        private String altText;
        private int widthPixels;
        private int heightPixels;

        public ImageData(byte[] bytes) {
            this.bytes = bytes;
            this.filename = "image.png";
            this.altText = "Image";
            // Default size: 200x100 pixels
            this.widthPixels = 200;
            this.heightPixels = 100;
        }

        public ImageData(byte[] bytes, int widthPixels, int heightPixels) {
            this.bytes = bytes;
            this.filename = "image.png";
            this.altText = "Image";
            this.widthPixels = widthPixels;
            this.heightPixels = heightPixels;
        }

        public ImageData(byte[] bytes, String filename, String altText,
                        int widthPixels, int heightPixels) {
            this.bytes = bytes;
            this.filename = filename;
            this.altText = altText;
            this.widthPixels = widthPixels;
            this.heightPixels = heightPixels;
        }

        // Convert pixels to EMU (English Metric Units)
        // 1 pixel = 9525 EMU (at 96 DPI)
        public int getWidthEMU() {
            return widthPixels * 9525;
        }

        public int getHeightEMU() {
            return heightPixels * 9525;
        }

        // Getters
        public byte[] getBytes() { return bytes; }
        public String getFilename() { return filename; }
        public String getAltText() { return altText; }
        public int getWidthPixels() { return widthPixels; }
        public int getHeightPixels() { return heightPixels; }

        // Setters for fluent API
        public ImageData filename(String filename) {
            this.filename = filename;
            return this;
        }

        public ImageData altText(String altText) {
            this.altText = altText;
            return this;
        }

        public ImageData size(int widthPixels, int heightPixels) {
            this.widthPixels = widthPixels;
            this.heightPixels = heightPixels;
            return this;
        }
    }
}
