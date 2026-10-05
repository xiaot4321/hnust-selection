package cn.hnust.selection.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

/** 私有名单文件存储契约；实际路径、安全随机键和磁盘写入由 service.impl 实现。 */
public interface PrivateFileStorage {
    StoredPrivateFile store(MultipartFile file) throws IOException;
    /** Store generated content under a random private key, outside the web root. */
    StoredPrivateFile storeBytes(String filename, String mediaType, byte[] contents) throws IOException;
    /** Store a student resume as a private PDF object; it remains unavailable until scanning completes. */
    StoredPrivateFile storeStudentResume(MultipartFile file) throws IOException;
    void delete(StoredPrivateFile file);
    void deleteByKey(String storageKey) throws IOException;
    /** Resolve a random managed storage key under the private root; never accepts a caller path. */
    Path resolveForRead(String storageKey) throws IOException;

    /** 返回给领域服务的存储元数据，不含任何公开下载地址。 */
    final class StoredPrivateFile {
        private final String key;
        private final String filename;
        private final String mediaType;
        private final long size;
        private final String digest;
        private final Path path;

        public StoredPrivateFile(String key, String filename, String mediaType, long size, String digest, Path path) {
            this.key = key;
            this.filename = filename;
            this.mediaType = mediaType;
            this.size = size;
            this.digest = digest;
            this.path = path;
        }

        public String getKey() { return key; }
        public String getFilename() { return filename; }
        public String getMediaType() { return mediaType; }
        public long getSize() { return size; }
        public String getDigest() { return digest; }
        public Path getPath() { return path; }
    }
}
