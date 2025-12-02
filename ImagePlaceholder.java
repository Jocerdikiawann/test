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
     * UPDATED: Proper sizing & wrap behind text support
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

        // Get actual image dimensions
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
            new java.io.ByteArrayInputStream(imageBytes)
        );

        int actualWidth = img.getWidth();
        int actualHeight = img.getHeight();

        // Calculate target dimensions maintaining aspect ratio
        int targetWidth = imageData.getWidthPixels();
        int targetHeight = imageData.getHeightPixels();

        if (imageData.isMaintainAspectRatio()) {
            // Calculate aspect ratio
            double aspectRatio = (double) actualWidth / actualHeight;

            // Fit within target dimensions
            if (targetWidth / aspectRatio <= targetHeight) {
                targetHeight = (int) (targetWidth / aspectRatio);
            } else {
                targetWidth = (int) (targetHeight * aspectRatio);
            }
        }

        // Convert to EMU
        int imageWidthEMU = targetWidth * 9525;
        int imageHeightEMU = targetHeight * 9525;

        // Create run
        R run = factory.createR();

        if (imageData.getWrapStyle() == WrapStyle.BEHIND_TEXT ||
            imageData.getWrapStyle() == WrapStyle.IN_FRONT_OF_TEXT) {
            // Use ANCHOR for behind/in-front text wrapping
            org.docx4j.dml.wordprocessingDrawing.Anchor anchor =
                createAnchoredImage(
                    wordMLPackage,
                    imagePart,
                    imageData,
                    imageWidthEMU,
                    imageHeightEMU
                );

            Drawing drawing = factory.createDrawing();
            drawing.getAnchorOrInline().add(anchor);
            run.getContent().add(drawing);
        } else {
            // Use INLINE for normal inline wrapping
            Inline inline = imagePart.createImageInline(
                imageData.getFilename(),
                imageData.getAltText(),
                0,
                1,
                false
            );

            // Set size
            inline.getExtent().setCx(imageWidthEMU);
            inline.getExtent().setCy(imageHeightEMU);

            Drawing drawing = factory.createDrawing();
            drawing.getAnchorOrInline().add(inline);
            run.getContent().add(drawing);
        }

        return run;
    }

    /**
     * Create anchored image (for behind text / in front of text)
     */
    private org.docx4j.dml.wordprocessingDrawing.Anchor createAnchoredImage(
            WordprocessingMLPackage wordMLPackage,
            BinaryPartAbstractImage imagePart,
            ImageData imageData,
            int widthEMU,
            int heightEMU
    ) throws Exception {

        org.docx4j.dml.wordprocessingDrawing.Anchor anchor =
            new org.docx4j.dml.wordprocessingDrawing.Anchor();

        // Set dimensions
        anchor.setDistT(0L);
        anchor.setDistB(0L);
        anchor.setDistL(0L);
        anchor.setDistR(0L);

        anchor.setSimplePos(false);
        anchor.setRelativeHeight(imageData.getZIndex());
        anchor.setAllowOverlap(true);
        anchor.setBehindDoc(imageData.getWrapStyle() == WrapStyle.BEHIND_TEXT);
        anchor.setLocked(false);
        anchor.setLayoutInCell(true);

        // Simple position
        org.docx4j.dml.wordprocessingDrawing.CTSimplePos simplePos =
            new org.docx4j.dml.wordprocessingDrawing.CTSimplePos();
        simplePos.setX(0L);
        simplePos.setY(0L);
        anchor.setSimplePos2(simplePos);

        // Horizontal positioning
        org.docx4j.dml.wordprocessingDrawing.CTPosH posH =
            new org.docx4j.dml.wordprocessingDrawing.CTPosH();
        posH.setRelativeFrom(
            org.docx4j.dml.wordprocessingDrawing.STRelFromH.COLUMN
        );
        org.docx4j.dml.wordprocessingDrawing.CTPosH.PosOffset posOffsetH =
            new org.docx4j.dml.wordprocessingDrawing.CTPosH.PosOffset();
        posOffsetH.setValue(imageData.getOffsetX());
        posH.setPosOffset(posOffsetH);
        anchor.setPositionH(posH);

        // Vertical positioning
        org.docx4j.dml.wordprocessingDrawing.CTPosV posV =
            new org.docx4j.dml.wordprocessingDrawing.CTPosV();
        posV.setRelativeFrom(
            org.docx4j.dml.wordprocessingDrawing.STRelFromV.PARAGRAPH
        );
        org.docx4j.dml.wordprocessingDrawing.CTPosV.PosOffset posOffsetV =
            new org.docx4j.dml.wordprocessingDrawing.CTPosV.PosOffset();
        posOffsetV.setValue(imageData.getOffsetY());
        posV.setPosOffset(posOffsetV);
        anchor.setPositionV(posV);

        // Extent
        org.docx4j.dml.wordprocessingDrawing.CTPositiveSize2D extent =
            new org.docx4j.dml.wordprocessingDrawing.CTPositiveSize2D();
        extent.setCx(widthEMU);
        extent.setCy(heightEMU);
        anchor.setExtent(extent);

        // Effect extent (no effects)
        org.docx4j.dml.CTEffectExtent effectExtent =
            new org.docx4j.dml.CTEffectExtent();
        effectExtent.setL(0L);
        effectExtent.setT(0L);
        effectExtent.setR(0L);
        effectExtent.setB(0L);
        anchor.setEffectExtent(effectExtent);

        // Wrap style
        if (imageData.getWrapStyle() == WrapStyle.BEHIND_TEXT) {
            org.docx4j.dml.wordprocessingDrawing.CTWrapNone wrapNone =
                new org.docx4j.dml.wordprocessingDrawing.CTWrapNone();
            anchor.setWrapNone(wrapNone);
        } else {
            org.docx4j.dml.wordprocessingDrawing.CTWrapSquare wrapSquare =
                new org.docx4j.dml.wordprocessingDrawing.CTWrapSquare();
            wrapSquare.setWrapText(
                org.docx4j.dml.wordprocessingDrawing.STWrapText.BOTH_SIDES
            );
            anchor.setWrapSquare(wrapSquare);
        }

        // Doc properties
        org.docx4j.dml.wordprocessingDrawing.CTNonVisualDrawingProps docPr =
            new org.docx4j.dml.wordprocessingDrawing.CTNonVisualDrawingProps();
        docPr.setId(1L);
        docPr.setName(imageData.getFilename());
        docPr.setDescr(imageData.getAltText());
        anchor.setDocPr(docPr);

        // Graphic
        org.docx4j.dml.Graphic graphic = new org.docx4j.dml.Graphic();
        org.docx4j.dml.GraphicData graphicData = new org.docx4j.dml.GraphicData();
        graphicData.setUri("http://schemas.openxmlformats.org/drawingml/2006/picture");

        org.docx4j.dml.picture.Pic pic = new org.docx4j.dml.picture.Pic();

        // Non-visual properties
        org.docx4j.dml.picture.Pic.NvPicPr nvPicPr =
            new org.docx4j.dml.picture.Pic.NvPicPr();
        org.docx4j.dml.picture.CTPictureNonVisual cNvPicPr =
            new org.docx4j.dml.picture.CTPictureNonVisual();
        cNvPicPr.setId(0L);
        cNvPicPr.setName(imageData.getFilename());
        nvPicPr.setCNvPicPr(cNvPicPr);

        org.docx4j.dml.CTNonVisualDrawingProps cNvPr =
            new org.docx4j.dml.CTNonVisualDrawingProps();
        cNvPr.setId(0L);
        cNvPr.setName(imageData.getFilename());
        nvPicPr.setCNvPr(cNvPr);
        pic.setNvPicPr(nvPicPr);

        // Blip fill
        org.docx4j.dml.picture.Pic.BlipFill blipFill =
            new org.docx4j.dml.picture.Pic.BlipFill();
        org.docx4j.dml.CTBlip blip = new org.docx4j.dml.CTBlip();
        blip.setEmbed(imagePart.getRelationshipType());
        blipFill.setBlip(blip);

        org.docx4j.dml.CTStretchInfoProperties stretch =
            new org.docx4j.dml.CTStretchInfoProperties();
        org.docx4j.dml.CTRelativeRect fillRect = new org.docx4j.dml.CTRelativeRect();
        stretch.setFillRect(fillRect);
        blipFill.setStretch(stretch);
        pic.setBlipFill(blipFill);

        // Shape properties
        org.docx4j.dml.picture.Pic.SpPr spPr = new org.docx4j.dml.picture.Pic.SpPr();
        org.docx4j.dml.CTTransform2D xfrm = new org.docx4j.dml.CTTransform2D();
        org.docx4j.dml.CTPoint2D off = new org.docx4j.dml.CTPoint2D();
        off.setX(0L);
        off.setY(0L);
        xfrm.setOff(off);

        org.docx4j.dml.CTPositiveSize2D ext = new org.docx4j.dml.CTPositiveSize2D();
        ext.setCx(widthEMU);
        ext.setCy(heightEMU);
        xfrm.setExt(ext);
        spPr.setXfrm(xfrm);

        org.docx4j.dml.CTPresetGeometry2D prstGeom =
            new org.docx4j.dml.CTPresetGeometry2D();
        prstGeom.setPrst(org.docx4j.dml.STShapeType.RECT);
        spPr.setPrstGeom(prstGeom);
        pic.setSpPr(spPr);

        graphicData.getAny().add(
            new org.docx4j.jaxb.Context().getWmlObjectFactory().createPic(pic)
        );
        graphic.setGraphicData(graphicData);
        anchor.setGraphic(graphic);

        return anchor;
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
     * Image data class with wrap style support
     */
    public static class ImageData {
        private byte[] bytes;
        private String filename;
        private String altText;
        private int widthPixels;
        private int heightPixels;
        private boolean maintainAspectRatio = true;
        private WrapStyle wrapStyle = WrapStyle.INLINE;
        private int zIndex = 251658240; // Default behind text
        private long offsetX = 0L;
        private long offsetY = 0L;

        public ImageData(byte[] bytes) {
            this.bytes = bytes;
            this.filename = "image.png";
            this.altText = "Image";
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
        public boolean isMaintainAspectRatio() { return maintainAspectRatio; }
        public WrapStyle getWrapStyle() { return wrapStyle; }
        public int getZIndex() { return zIndex; }
        public long getOffsetX() { return offsetX; }
        public long getOffsetY() { return offsetY; }

        // Fluent setters
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

        public ImageData maintainAspectRatio(boolean maintain) {
            this.maintainAspectRatio = maintain;
            return this;
        }

        /**
         * Set wrap style to BEHIND_TEXT (image dibelakang text)
         */
        public ImageData behindText() {
            this.wrapStyle = WrapStyle.BEHIND_TEXT;
            return this;
        }

        /**
         * Set wrap style to IN_FRONT_OF_TEXT
         */
        public ImageData inFrontOfText() {
            this.wrapStyle = WrapStyle.IN_FRONT_OF_TEXT;
            return this;
        }

        /**
         * Set wrap style to INLINE (default)
         */
        public ImageData inline() {
            this.wrapStyle = WrapStyle.INLINE;
            return this;
        }

        public ImageData wrapStyle(WrapStyle wrapStyle) {
            this.wrapStyle = wrapStyle;
            return this;
        }

        public ImageData zIndex(int zIndex) {
            this.zIndex = zIndex;
            return this;
        }

        public ImageData offset(long x, long y) {
            this.offsetX = x;
            this.offsetY = y;
            return this;
        }
    }

    /**
     * Wrap style enum
     */
    public enum WrapStyle {
        INLINE,           // Normal inline (default)
        BEHIND_TEXT,      // Behind text (watermark-like)
        IN_FRONT_OF_TEXT, // In front of text
        SQUARE,           // Text wraps around square
        TIGHT,            // Text wraps tight
        THROUGH,          // Text flows through
        TOP_AND_BOTTOM    // Text above and below only
    }
}
