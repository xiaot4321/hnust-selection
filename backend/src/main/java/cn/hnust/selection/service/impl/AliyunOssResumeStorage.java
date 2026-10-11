package cn.hnust.selection.service.impl;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import java.util.UUID;
import java.util.regex.Pattern;

/** Private Aliyun OSS adapter for student resume and teacher portrait objects. */
@Component
public class AliyunOssResumeStorage {
    private static final String STORAGE_KEY_PREFIX = "oss_";
    private static final String RESUME_OBJECT_KEY_PREFIX = "student-resumes/";
    private static final String AVATAR_OBJECT_KEY_PREFIX = "teacher-avatars/";
    private static final Pattern MANAGED_KEY = Pattern.compile("^oss_[0-9a-f]{32}\\.(pdf|jpg)$");
    private static final Pattern AVATAR_KEY = Pattern.compile("^oss_[0-9a-f]{32}\\.jpg$");

    private final String endpoint;
    private final String bucket;
    private final String accessKeyId;
    private final String accessKeySecret;
    private volatile OSS client;

    public AliyunOssResumeStorage(
        @Value("${app.oss.endpoint:https://oss-cn-beijing.aliyuncs.com}") String endpoint,
        @Value("${app.oss.bucket:hnust-selection}") String bucket,
        @Value("${app.oss.access-key-id:}") String accessKeyId,
        @Value("${app.oss.access-key-secret:}") String accessKeySecret) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
    }

    public String store(byte[] contents) throws IOException {
        if (contents == null || contents.length == 0) throw new IOException("简历内容为空");
        String storageKey = STORAGE_KEY_PREFIX + UUID.randomUUID().toString().replace("-", "") + ".pdf";
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(contents.length);
        metadata.setContentType("application/pdf");
        metadata.setObjectAcl(CannedAccessControlList.Private);
        try {
            getClient().putObject(new PutObjectRequest(bucket, toObjectKey(storageKey),
                new ByteArrayInputStream(contents), metadata));
            return storageKey;
        } catch (OSSException | ClientException ex) {
            throw new IOException("上传简历到阿里云 OSS 失败", ex);
        }
    }

    public String storeTeacherProfileAvatar(byte[] contents) throws IOException {
        if (contents == null || contents.length == 0) throw new IOException("头像内容为空");
        String storageKey = STORAGE_KEY_PREFIX + UUID.randomUUID().toString().replace("-", "") + ".jpg";
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(contents.length);
        metadata.setContentType("image/jpeg");
        metadata.setObjectAcl(CannedAccessControlList.Private);
        try {
            getClient().putObject(new PutObjectRequest(bucket, toObjectKey(storageKey),
                new ByteArrayInputStream(contents), metadata));
            return storageKey;
        } catch (OSSException | ClientException ex) {
            throw new IOException("上传导师头像到阿里云 OSS 失败", ex);
        }
    }

    public InputStream open(String storageKey) throws IOException {
        try {
            OSSObject object = getClient().getObject(bucket, toObjectKey(storageKey));
            return object.getObjectContent();
        } catch (OSSException | ClientException ex) {
            throw new IOException("从阿里云 OSS 读取简历失败", ex);
        }
    }

    public void delete(String storageKey) throws IOException {
        try {
            getClient().deleteObject(bucket, toObjectKey(storageKey));
        } catch (OSSException | ClientException ex) {
            throw new IOException("从阿里云 OSS 删除简历失败", ex);
        }
    }

    public String temporaryReadUrl(String storageKey) throws IOException {
        if (!AVATAR_KEY.matcher(storageKey == null ? "" : storageKey).matches()) {
            throw new IOException("无效的导师头像存储键");
        }
        try {
            URL url = getClient().generatePresignedUrl(bucket, toObjectKey(storageKey),
                new Date(System.currentTimeMillis() + 15L * 60L * 1000L));
            return url.toExternalForm();
        } catch (OSSException | ClientException ex) {
            throw new IOException("无法生成导师头像的临时访问地址", ex);
        }
    }

    public static boolean isManagedKey(String storageKey) {
        return storageKey != null && MANAGED_KEY.matcher(storageKey).matches();
    }

    private static String toObjectKey(String storageKey) throws IOException {
        if (!isManagedKey(storageKey)) throw new IOException("无效的简历存储键");
        String prefix = AVATAR_KEY.matcher(storageKey).matches() ? AVATAR_OBJECT_KEY_PREFIX : RESUME_OBJECT_KEY_PREFIX;
        return prefix + storageKey.substring(STORAGE_KEY_PREFIX.length());
    }

    private OSS getClient() throws IOException {
        OSS current = client;
        if (current != null) return current;
        synchronized (this) {
            if (client != null) return client;
            String configuredAccessKeyId = trimToNull(accessKeyId);
            String configuredAccessKeySecret = trimToNull(accessKeySecret);
            if (configuredAccessKeyId == null || configuredAccessKeySecret == null) {
                throw new IOException("未配置 OSS_ACCESS_KEY_ID 和 OSS_ACCESS_KEY_SECRET 环境变量");
            }
            if (endpoint == null || !endpoint.trim().toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
                throw new IOException("OSS_ENDPOINT 必须使用 HTTPS");
            }
            if (bucket == null || bucket.trim().isEmpty()) throw new IOException("OSS_BUCKET 未配置");
            try {
                client = new OSSClientBuilder().build(endpoint, configuredAccessKeyId, configuredAccessKeySecret);
                return client;
            } catch (RuntimeException ex) {
                throw new IOException("无法初始化阿里云 OSS 客户端", ex);
            }
        }
    }

    @PreDestroy
    public void close() {
        OSS current = client;
        if (current != null) current.shutdown();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
