package com.documenter.service.impl;

import com.documenter.configuration.StorageProperties;
import com.documenter.dto.EditImageDTO;
import com.documenter.entity.DocumentVersion;
import com.documenter.enums.ChangeType;
import com.documenter.exception.BusinessException;
import com.documenter.service.DocumentVersionService;
import com.documenter.service.FileStorage;
import com.documenter.service.ImageDocumentService;
import com.documenter.service.VersionWriter;
import com.documenter.util.Digests;
import com.documenter.vo.FileDownload;
import com.documenter.vo.VersionVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/** 使用 JDK ImageIO 和 Java2D 完成本地 PNG/JPEG 编辑。 */
@Slf4j
@Service
public class ImageDocumentServiceImpl implements ImageDocumentService {
    private final DocumentVersionService versionService;
    private final FileStorage fileStorage;
    private final VersionWriter versionWriter;
    private final StorageProperties properties;

    public ImageDocumentServiceImpl(DocumentVersionService versionService, FileStorage fileStorage,
                                    VersionWriter versionWriter, StorageProperties properties) {
        this.versionService = versionService;
        this.fileStorage = fileStorage;
        this.versionWriter = versionWriter;
        this.properties = properties;
    }

    @Override
    public VersionVO edit(Long userId, Long fileId, Integer sourceVersion, EditImageDTO request) {
        validateRequest(request);
        FileDownload source = versionService.downloadVersion(userId, fileId, sourceVersion);
        byte[] edited;
        String outputFormat;
        int width;
        int height;
        try (InputStream input = source.resource().getInputStream();
             MemoryCacheImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
            String sourceFormat = sourceFormat(source.contentType());
            if (source.contentLength() > properties.getUpload().getMaxSizeBytes()) {
                throw new BusinessException(413, "源图片超过文件大小限制");
            }
            outputFormat = request.getOutputFormat() == null ? sourceFormat : request.getOutputFormat();
            if (request.getJpegQuality() != null && !"JPEG".equals(outputFormat)) {
                throw new BusinessException(400, "jpegQuality 只能用于 JPEG 输出");
            }
            BufferedImage image = decode(imageInput, sourceFormat);
            for (EditImageDTO.Operation operation : request.getOperations()) {
                image = applyOperation(image, operation);
            }
            width = image.getWidth();
            height = image.getHeight();
            edited = encode(image, outputFormat, request.getJpegQuality());
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("编辑图片失败, fileId={}, sourceVersion={}", fileId, sourceVersion, e);
            throw new BusinessException(422, "图片无法解码或处理失败");
        }
        if (edited.length > properties.getUpload().getMaxSizeBytes()) {
            throw new BusinessException(413, "编辑后的图片超过文件大小限制");
        }
        String extension = "PNG".equals(outputFormat) ? "png" : "jpg";
        String contentType = "PNG".equals(outputFormat) ? "image/png" : "image/jpeg";
        String storageKey = fileStorage.store(edited, extension);
        try {
            boolean converted = !contentType.equalsIgnoreCase(source.contentType());
            DocumentVersion version = versionWriter.appendVersion(fileId, userId,
                    new VersionWriter.VersionContent(storageKey, edited.length, Digests.sha256Hex(edited),
                            contentType, extension),
                    converted ? ChangeType.CONVERT : ChangeType.MANUAL_EDIT,
                    request.getInstruction(), "图片编辑结果：" + width + "×" + height + "，" + outputFormat,
                    sourceVersion);
            return VersionVO.from(version);
        } catch (RuntimeException e) {
            if (!fileStorage.delete(storageKey)) {
                log.warn("新增图片版本失败且无法清理产物, storageKey={}", storageKey);
            }
            throw e;
        }
    }

    private static String sourceFormat(String contentType) {
        if ("image/png".equalsIgnoreCase(contentType)) {
            return "PNG";
        }
        if ("image/jpeg".equalsIgnoreCase(contentType)) {
            return "JPEG";
        }
        throw new BusinessException(400, "当前版本不是支持编辑的 PNG/JPEG 图片");
    }

    private BufferedImage decode(MemoryCacheImageInputStream input, String expectedFormat)
            throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) {
            throw new BusinessException(422, "图片文件无法解码或内容已损坏");
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(input, true, true);
            if (!expectedFormat.equalsIgnoreCase(reader.getFormatName())) {
                throw new BusinessException(422, "图片实际格式与版本元数据不一致");
            }
            checkDimensions(reader.getWidth(0), reader.getHeight(0));
            BufferedImage image = reader.read(0);
            if (image == null) {
                throw new BusinessException(422, "图片解码结果为空");
            }
            return image;
        } finally {
            reader.dispose();
        }
    }

    private BufferedImage applyOperation(BufferedImage source, EditImageDTO.Operation operation) {
        int width = source.getWidth();
        int height = source.getHeight();
        return switch (operation.getType()) {
            case "CROP" -> {
                if ((long) operation.getX() + operation.getWidth() > width
                        || (long) operation.getY() + operation.getHeight() > height) {
                    throw new BusinessException(400, "裁剪范围超出当前图片");
                }
                BufferedImage cropped = newImage(source, operation.getWidth(), operation.getHeight());
                Graphics2D graphics = cropped.createGraphics();
                try {
                    graphics.setComposite(AlphaComposite.Src);
                    graphics.drawImage(source, 0, 0, cropped.getWidth(), cropped.getHeight(),
                            operation.getX(), operation.getY(), operation.getX() + cropped.getWidth(),
                            operation.getY() + cropped.getHeight(), null);
                } finally {
                    graphics.dispose();
                }
                yield cropped;
            }
            case "RESIZE" -> {
                BufferedImage resized = newImage(source, operation.getWidth(), operation.getHeight());
                Graphics2D graphics = resized.createGraphics();
                try {
                    graphics.setComposite(AlphaComposite.Src);
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(source, 0, 0, resized.getWidth(), resized.getHeight(), null);
                } finally {
                    graphics.dispose();
                }
                yield resized;
            }
            case "ROTATE" -> {
                int degrees = operation.getDegrees();
                BufferedImage rotated = newImage(source, degrees == 180 ? width : height,
                        degrees == 180 ? height : width);
                Graphics2D graphics = rotated.createGraphics();
                try {
                    graphics.setComposite(AlphaComposite.Src);
                    if (degrees == 90) {
                        graphics.translate(height, 0);
                    } else if (degrees == 180) {
                        graphics.translate(width, height);
                    } else {
                        graphics.translate(0, width);
                    }
                    graphics.rotate(Math.toRadians(degrees));
                    graphics.drawImage(source, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                yield rotated;
            }
            case "BRIGHTNESS" -> adjustBrightness(source, operation.getFactor());
            case "ADD_TEXT" -> addText(source, operation);
            default -> throw new BusinessException(400, "不支持的图片操作");
        };
    }

    private BufferedImage adjustBrightness(BufferedImage source, double factor) {
        BufferedImage adjusted = newImage(source, source.getWidth(), source.getHeight());
        int[] row = new int[source.getWidth()];
        for (int y = 0; y < source.getHeight(); y++) {
            source.getRGB(0, y, row.length, 1, row, 0, row.length);
            for (int x = 0; x < row.length; x++) {
                int pixel = row[x];
                int red = brighten((pixel >>> 16) & 255, factor);
                int green = brighten((pixel >>> 8) & 255, factor);
                int blue = brighten(pixel & 255, factor);
                row[x] = (pixel & 0xff000000) | (red << 16) | (green << 8) | blue;
            }
            adjusted.setRGB(0, y, row.length, 1, row, 0, row.length);
        }
        return adjusted;
    }

    private static int brighten(int channel, double factor) {
        return Math.min(255, (int) Math.round(channel * factor));
    }

    private BufferedImage addText(BufferedImage source, EditImageDTO.Operation operation) {
        BufferedImage result = newImage(source, source.getWidth(), source.getHeight());
        Graphics2D graphics = result.createGraphics();
        try {
            Font font = new Font(Font.SANS_SERIF, Font.PLAIN,
                    operation.getFontSize() == null ? 24 : operation.getFontSize());
            if (font.canDisplayUpTo(operation.getText()) != -1) {
                throw new BusinessException(422, "当前系统字体无法显示部分文字，请安装包含对应字符的字体后重试");
            }
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            TextLayout layout = new TextLayout(operation.getText(), font, graphics.getFontRenderContext());
            Rectangle ink = layout.getPixelBounds(graphics.getFontRenderContext(), 0, 0);
            // x/y 定义为实际文字像素边界的左上角，不使用易混淆的文字基线。
            if (ink.isEmpty() || (long) operation.getX() + ink.width > source.getWidth()
                    || (long) operation.getY() + ink.height > source.getHeight()) {
                throw new BusinessException(400, "文字范围超出当前图片或没有可见文字；请调整位置、字号或内容");
            }
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, null);
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setColor(operation.getColor() == null ? Color.BLACK : Color.decode(operation.getColor()));
            layout.draw(graphics, operation.getX() - (float) ink.x, operation.getY() - (float) ink.y);
            return result;
        } finally {
            graphics.dispose();
        }
    }

    private BufferedImage newImage(BufferedImage source, int width, int height) {
        checkDimensions(width, height);
        return new BufferedImage(width, height, source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
    }

    private void checkDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new BusinessException(400, "图片尺寸必须大于 0");
        }
        long maxPixels = properties.getUpload().getMaxImagePixels();
        if (maxPixels > 0 && (long) width * height > maxPixels) {
            throw new BusinessException(413, "图片像素超过处理限制");
        }
    }

    private static byte[] encode(BufferedImage image, String format, Double quality) throws IOException {
        if ("JPEG".equals(format)) {
            BufferedImage flattened = new BufferedImage(image.getWidth(), image.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = flattened.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            image = flattened;
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            throw new BusinessException(500, "当前环境不支持图片输出编码");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream imageOutput = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if ("JPEG".equals(format)) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(quality == null ? 0.9f : quality.floatValue());
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static void validateRequest(EditImageDTO request) {
        if (request == null || request.getOperations() == null || request.getOperations().size() > 20) {
            throw new BusinessException(400, "图片编辑操作无效");
        }
        if (request.getOperations().isEmpty() && request.getOutputFormat() == null
                && request.getJpegQuality() == null) {
            throw new BusinessException(400, "至少提供一个图片操作、输出格式或 JPEG 质量");
        }
        if (request.getOutputFormat() != null && !"PNG".equals(request.getOutputFormat())
                && !"JPEG".equals(request.getOutputFormat())) {
            throw new BusinessException(400, "输出格式只能是 PNG 或 JPEG");
        }
        if (request.getJpegQuality() != null && (!Double.isFinite(request.getJpegQuality())
                || request.getJpegQuality() < 0.1 || request.getJpegQuality() > 1)) {
            throw new BusinessException(400, "JPEG 质量必须在 0.1 到 1 之间");
        }
        for (EditImageDTO.Operation operation : request.getOperations()) {
            if (operation == null || operation.getType() == null) {
                throw new BusinessException(400, "图片操作不能为空");
            }
            boolean size = operation.getWidth() != null && operation.getWidth() > 0
                    && operation.getHeight() != null && operation.getHeight() > 0;
            boolean noText = operation.getText() == null && operation.getFontSize() == null
                    && operation.getColor() == null;
            boolean noSize = operation.getWidth() == null && operation.getHeight() == null;
            boolean noPosition = operation.getX() == null && operation.getY() == null;
            boolean valid = switch (operation.getType()) {
                case "CROP" -> size && operation.getX() != null && operation.getX() >= 0
                        && operation.getY() != null && operation.getY() >= 0 && operation.getDegrees() == null
                        && operation.getFactor() == null && noText;
                case "RESIZE" -> size && noPosition && operation.getDegrees() == null
                        && operation.getFactor() == null && noText;
                case "ROTATE" -> noPosition && noSize && operation.getFactor() == null && noText
                        && operation.getDegrees() != null && (operation.getDegrees() == 90
                        || operation.getDegrees() == 180 || operation.getDegrees() == 270);
                case "BRIGHTNESS" -> noPosition && noSize && noText && operation.getDegrees() == null
                        && operation.getFactor() != null && Double.isFinite(operation.getFactor())
                        && operation.getFactor() >= 0 && operation.getFactor() <= 4;
                case "ADD_TEXT" -> noSize && operation.getDegrees() == null && operation.getFactor() == null
                        && operation.getX() != null && operation.getX() >= 0
                        && operation.getY() != null && operation.getY() >= 0
                        && operation.getText() != null && !operation.getText().isBlank()
                        && operation.getText().length() <= 500 && operation.getText().codePoints().noneMatch(
                                code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029)
                        && (operation.getFontSize() == null || (operation.getFontSize() >= 1 && operation.getFontSize() <= 512))
                        && (operation.getColor() == null || operation.getColor().matches("^#[0-9a-fA-F]{6}$"));
                default -> false;
            };
            if (!valid) {
                throw new BusinessException(400, "图片操作字段不完整或包含不适用的字段");
            }
        }
    }
}
