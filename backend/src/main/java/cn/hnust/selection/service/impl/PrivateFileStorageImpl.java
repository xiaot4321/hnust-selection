package cn.hnust.selection.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import cn.hnust.selection.service.PrivateFileStorage;
import cn.hnust.selection.service.PrivateFileStorage.StoredPrivateFile;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Pattern;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * 管理名单原始文件的私有磁盘适配器。
 *
 * <p>目录由环境变量配置，默认位于 backend/var/private-files，Web 静态资源不会公开该目录。
 * 数据库中只保存随机 storageKey，不把磁盘绝对路径作为下载 URL。</p>
 */
@Service
public class PrivateFileStorageImpl implements PrivateFileStorage {
    private static final Pattern STORAGE_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,255}$");
    private final Path root;
    private final AliyunOssResumeStorage ossResumeStorage;
    public PrivateFileStorageImpl(@Value("${app.private-storage.directory:./var/private-files}") String directory,
                                  AliyunOssResumeStorage ossResumeStorage) {
        this.root = Paths.get(directory).toAbsolutePath().normalize();
        this.ossResumeStorage = ossResumeStorage;
    }

    @Override
    public StoredPrivateFile store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IOException("请先选择名单文件");
        if (file.getSize() > 10L * 1024L * 1024L) throw new IOException("名单文件不能超过 10 MB");
        String originalName = file.getOriginalFilename() == null ? "personnel-upload" : file.getOriginalFilename();
        String safeName = Paths.get(originalName.replace('\\', '/')).getFileName().toString();
        String key = UUID.randomUUID().toString().replace("-", "") + "." + extension(safeName);
        Files.createDirectories(root);
        Path destination = root.resolve(key).normalize();
        if (!destination.startsWith(root)) throw new IOException("无效的私有存储键");
        byte[] contents = file.getBytes();
        Files.write(destination, contents, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new StoredPrivateFile(key, safeName, mediaType(safeName), contents.length, sha256(contents), destination);
    }

    @Override
    public StoredPrivateFile storeBytes(String filename, String contentType, byte[] contents) throws IOException {
        if (contents == null) throw new IOException("缺少待保存文件内容");
        String safeName = filename == null ? "export.csv" : Paths.get(filename.replace('\\', '/')).getFileName().toString();
        if (safeName.length() > 255 || !safeName.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
            throw new IOException("导出文件名无效");
        }
        String key = UUID.randomUUID().toString().replace("-", "") + ".csv";
        Files.createDirectories(root);
        Path destination = root.resolve(key).normalize();
        if (!destination.startsWith(root)) throw new IOException("无效的私有存储键");
        Files.write(destination, contents, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new StoredPrivateFile(key, safeName,
            contentType == null ? "text/csv; charset=UTF-8" : contentType, contents.length, sha256(contents), destination);
    }

    @Override
    public StoredPrivateFile storeStudentResume(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IOException("请选择 PDF 简历文件");
        if (file.getSize() > 10L * 1024L * 1024L) throw new IOException("简历文件不能超过 10 MB");
        String originalName = file.getOriginalFilename() == null ? "resume.pdf" : file.getOriginalFilename();
        String safeName = Paths.get(originalName.replace('\\', '/')).getFileName().toString();
        if (!safeName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) throw new IOException("简历只接受 PDF 文件");
        if (safeName.length() > 255) throw new IOException("文件名超出长度限制");
        byte[] contents = file.getBytes();
        if (contents.length < 5 || contents[0] != '%' || contents[1] != 'P' || contents[2] != 'D' || contents[3] != 'F' || contents[4] != '-') {
            throw new IOException("文件内容不是有效的 PDF 格式");
        }
        String key = ossResumeStorage.store(contents);
        return new StoredPrivateFile(key, safeName, "application/pdf", contents.length, sha256(contents), null);
    }

    @Override
    public StoredPrivateFile storeTeacherProfileAvatar(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IOException("请选择导师头像");
        if (file.getSize() > 5L * 1024L * 1024L) throw new IOException("头像文件不能超过 5 MB");
        byte[] contents = normalizeAvatar(file.getBytes());
        if (contents.length > 5L * 1024L * 1024L) throw new IOException("处理后的头像不能超过 5 MB");
        String key = ossResumeStorage.storeTeacherProfileAvatar(contents);
        return new StoredPrivateFile(key, "teacher-avatar.jpg", "image/jpeg", contents.length, sha256(contents), null);
    }

    @Override
    public void delete(StoredPrivateFile file) {
        if (file == null) return;
        try { deleteByKey(file.getKey()); } catch (IOException ignored) { /* 不以清理失败覆盖主业务异常。 */ }
    }

    @Override
    public void deleteByKey(String storageKey) throws IOException {
        if (AliyunOssResumeStorage.isManagedKey(storageKey)) {
            ossResumeStorage.delete(storageKey);
            return;
        }
        Files.deleteIfExists(resolveForRead(storageKey));
    }

    @Override
    public Resource openForRead(String storageKey) throws IOException {
        if (AliyunOssResumeStorage.isManagedKey(storageKey)) {
            return new InputStreamResource(ossResumeStorage.open(storageKey));
        }
        Path path = resolveForRead(storageKey);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) throw new IOException("私有文件不存在");
        return new FileSystemResource(path);
    }

    @Override
    public String temporaryReadUrl(String storageKey) throws IOException {
        if (!AliyunOssResumeStorage.isManagedKey(storageKey) || !storageKey.endsWith(".jpg")) {
            throw new IOException("无效的导师头像存储键");
        }
        return ossResumeStorage.temporaryReadUrl(storageKey);
    }

    @Override
    public Path resolveForRead(String storageKey) throws IOException {
        if (AliyunOssResumeStorage.isManagedKey(storageKey)) throw new IOException("OSS 对象不能解析为本地路径");
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches() || storageKey.contains("..")) {
            throw new IOException("无效的私有存储键");
        }
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) throw new IOException("无效的私有存储键");
        return target;
    }

    private static String extension(String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        if (!"csv".equals(ext) && !"xlsx".equals(ext)) throw new IOException("仅接受 .csv 或 .xlsx 文件");
        return ext;
    }
    private static String mediaType(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".csv") ? "text/csv" :
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    private static byte[] normalizeAvatar(byte[] sourceBytes) throws IOException {
        if (sourceBytes == null || sourceBytes.length == 0) throw new IOException("头像文件为空");
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(sourceBytes))) {
            if (imageInput == null) throw new IOException("无法读取头像文件");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw new IOException("头像只接受有效的 JPG 或 PNG 图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!"jpg".equals(format) && !"jpeg".equals(format) && !"png".equals(format)) {
                    throw new IOException("头像只接受 JPG 或 PNG 图片");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096 ||
                    (long) width * (long) height > 16000000L) {
                    throw new IOException("头像尺寸不能超过 4096 × 4096 像素");
                }
                BufferedImage source = reader.read(0);
                if (source == null) throw new IOException("头像图片无法解码");
                double scale = Math.min(1.0d, 1600.0d / Math.max(width, height));
                int outputWidth = Math.max(1, (int) Math.round(width * scale));
                int outputHeight = Math.max(1, (int) Math.round(height * scale));
                BufferedImage output = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = output.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    graphics.setColor(java.awt.Color.WHITE);
                    graphics.fillRect(0, 0, outputWidth, outputHeight);
                    graphics.drawImage(source, 0, 0, outputWidth, outputHeight, null);
                } finally {
                    graphics.dispose();
                    source.flush();
                }
                Iterator<javax.imageio.ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
                if (!writers.hasNext()) throw new IOException("当前环境不支持 JPEG 图片编码");
                javax.imageio.ImageWriter writer = writers.next();
                try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                     ImageOutputStream imageOutput = ImageIO.createImageOutputStream(bytes)) {
                    writer.setOutput(imageOutput);
                    ImageWriteParam parameters = writer.getDefaultWriteParam();
                    if (parameters.canWriteCompressed()) {
                        parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        parameters.setCompressionQuality(0.88f);
                    }
                    writer.write(null, new IIOImage(output, null, null), parameters);
                    imageOutput.flush();
                    return bytes.toByteArray();
                } finally {
                    writer.dispose();
                    output.flush();
                }
            } finally {
                reader.dispose();
            }
        }
    }
    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (Exception exception) { throw new IOException("无法计算文件摘要", exception); }
    }

}
