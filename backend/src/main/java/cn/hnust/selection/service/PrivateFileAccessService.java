package cn.hnust.selection.service;

import cn.hnust.selection.security.AccountPrincipal;
import org.springframework.core.io.Resource;

public interface PrivateFileAccessService {
    AuthorizedFile open(AccountPrincipal actor, Long fileId);
    final class AuthorizedFile {
        private final Resource resource; private final String filename; private final String mediaType; private final Long size;
        public AuthorizedFile(Resource resource, String filename, String mediaType, Long size) {
            this.resource=resource; this.filename=filename; this.mediaType=mediaType; this.size=size;
        }
        public Resource getResource() { return resource; }
        public String getFilename() { return filename; }
        public String getMediaType() { return mediaType; }
        public Long getSize() { return size; }
    }
}
