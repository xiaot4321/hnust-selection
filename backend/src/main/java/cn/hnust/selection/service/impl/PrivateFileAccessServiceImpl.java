package cn.hnust.selection.service.impl;

import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.PrivateFileAccessRepository;
import cn.hnust.selection.repository.PrivateFileAccessRepository.FileMetadata;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.PrivateFileAccessService;
import cn.hnust.selection.service.PrivateFileStorage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class PrivateFileAccessServiceImpl implements PrivateFileAccessService {
    private final PrivateFileAccessRepository repository;
    private final PrivateFileStorage storage;
    public PrivateFileAccessServiceImpl(PrivateFileAccessRepository repository, PrivateFileStorage storage) {
        this.repository=repository; this.storage=storage;
    }
    @Override
    @Transactional
    public AuthorizedFile open(AccountPrincipal actor, Long fileId) {
        if (actor == null || actor.getAccountId() == null || actor.getRole() == null) throw new ApiException("UNAUTHENTICATED", "请先登录", HttpStatus.UNAUTHORIZED);
        Long teacherId = actor.getRole() == AccountRole.TEACHER && actor.getIdentity() != null ? actor.getIdentity().getId() : null;
        FileMetadata metadata = repository.findAuthorized(fileId, actor.getAccountId(), actor.getRole(), teacherId);
        if (metadata == null) throw new ApiException("NOT_FOUND", "文件不存在或当前账号无权访问", HttpStatus.NOT_FOUND);
        try {
            Path path = storage.resolveForRead(metadata.storageKey);
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) throw new ApiException("NOT_FOUND", "文件不存在或当前账号无权访问", HttpStatus.NOT_FOUND);
            repository.insertAccessRecord(actor.getAccountId(), null, fileId,
                actor.getRole() == AccountRole.TEACHER ? "APPLICATION_SNAPSHOT;teacherId=" + teacherId : "FILE_OWNER");
            return new AuthorizedFile(new FileSystemResource(path), metadata.filename,
                metadata.mediaType == null ? "application/pdf" : metadata.mediaType, metadata.sizeBytes);
        } catch (IOException ex) {
            throw new ApiException("NOT_FOUND", "文件不存在或当前账号无权访问", HttpStatus.NOT_FOUND);
        }
    }
}
