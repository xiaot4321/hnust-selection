package cn.hnust.selection.service.impl;

import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.StudentProfileRepository;
import cn.hnust.selection.repository.StudentProfileRepository.ResumeUploadOperation;
import cn.hnust.selection.request.UpdateStudentProfileRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.PrivateFileStorage;
import cn.hnust.selection.service.PrivateFileStorage.StoredPrivateFile;
import cn.hnust.selection.service.StudentProfileService;
import cn.hnust.selection.vo.StudentProfileVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Service
public class StudentProfileServiceImpl implements StudentProfileService {
    private static final Pattern UUID_V4 = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private final StudentProfileRepository repository;
    private final PrivateFileStorage fileStorage;
    private final TransactionTemplate transactionTemplate;

    public StudentProfileServiceImpl(StudentProfileRepository repository, PrivateFileStorage fileStorage,
        PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.fileStorage = fileStorage;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    @Transactional(readOnly = true)
    public StudentProfileVO getProfile(AccountPrincipal actor) {
        Long studentId = requireStudent(actor);
        StudentProfileVO profile = repository.findCurrentProfile(studentId)
            .orElseThrow(() -> new ApiException("NOT_FOUND", "学生资料不存在", HttpStatus.NOT_FOUND));
        profile.setProfileEtag(etag(studentId, profile.getProfileVersion()));
        return profile;
    }

    @Override
    @Transactional
    public StudentProfileVO updateProfile(AccountPrincipal actor, String ifMatch,
                                          UpdateStudentProfileRequest request) {
        Long studentId = requireStudent(actor);
        if (ifMatch == null || ifMatch.trim().isEmpty()) {
            throw new ApiException("PRECONDITION_REQUIRED", "更新资料必须提供 If-Match", HttpStatus.PRECONDITION_REQUIRED);
        }
        if (!repository.lockStudent(studentId)) {
            throw new ApiException("NOT_FOUND", "学生资料不存在", HttpStatus.NOT_FOUND);
        }
        StudentProfileVO current = repository.findCurrentProfile(studentId)
            .orElseThrow(() -> new ApiException("NOT_FOUND", "学生资料不存在", HttpStatus.NOT_FOUND));
        if (!etag(studentId, current.getProfileVersion()).equals(ifMatch.trim())) {
            throw new ApiException("PRECONDITION_FAILED", "资料已更新，请重新读取后再保存", HttpStatus.PRECONDITION_FAILED);
        }

        int nextVersion = current.getProfileVersion() + 1;
        String biography = request.getBiography() == null ? current.getBiography() : request.getBiography();
        String contact = request.getContact() == null ? current.getContact() : request.getContact();
        Long resumeId = current.getResume() == null ? null : current.getResume().getFileId();
        repository.insertProfileVersion(studentId, nextVersion, biography, contact, resumeId, actor.getAccountId());
        StudentProfileVO updated = repository.findCurrentProfile(studentId)
            .orElseThrow(() -> new ApiException("INTERNAL_ERROR", "资料更新后无法读取", HttpStatus.INTERNAL_SERVER_ERROR));
        updated.setProfileEtag(etag(studentId, updated.getProfileVersion()));
        return updated;
    }

    @Override
    public StudentProfileVO.StudentResumeVO uploadResume(AccountPrincipal actor, String idempotencyKey, MultipartFile file) {
        Long studentId = requireStudent(actor);
        if (idempotencyKey == null || !UUID_V4.matcher(idempotencyKey.trim()).matches()) {
            throw new ApiException("INVALID_ARGUMENT", "Idempotency-Key 必须是 UUID v4", HttpStatus.BAD_REQUEST);
        }
        if (file == null || file.isEmpty()) throw new ApiException("INVALID_ARGUMENT", "请选择 PDF 简历文件", HttpStatus.BAD_REQUEST);
        if (file.getSize() > 10L * 1024L * 1024L) throw new ApiException("INVALID_ARGUMENT", "简历文件不能超过 10 MB", HttpStatus.BAD_REQUEST);
        String filename = file.getOriginalFilename() == null ? "resume.pdf" : file.getOriginalFilename();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) throw new ApiException("INVALID_ARGUMENT", "简历只接受 PDF 文件", HttpStatus.BAD_REQUEST);
        byte[] contents;
        try { contents = file.getBytes(); }
        catch (IOException ex) { throw new ApiException("INTERNAL_ERROR", "无法读取简历文件", HttpStatus.INTERNAL_SERVER_ERROR); }
        if (contents.length < 5 || contents[0] != '%' || contents[1] != 'P' || contents[2] != 'D' || contents[3] != 'F' || contents[4] != '-') {
            throw new ApiException("INVALID_ARGUMENT", "文件内容不是有效的 PDF 格式", HttpStatus.BAD_REQUEST);
        }
        String fingerprint = sha256("STUDENT_RESUME_UPLOAD|" + studentId + "|" + sha256(contents) + "|" + filename);
        final String requestId = idempotencyKey.trim().toLowerCase(Locale.ROOT);
        final AtomicReference<StoredPrivateFile> createdFile = new AtomicReference<StoredPrivateFile>();
        try {
            StudentProfileVO.StudentResumeVO result = transactionTemplate.execute(status -> {
                if (!repository.lockActiveStudent(studentId, actor.getAccountId())) {
                    throw new ApiException("FORBIDDEN", "当前学生账号不可上传简历", HttpStatus.FORBIDDEN);
                }
                Optional<ResumeUploadOperation> prior = repository.findResumeUploadOperation(actor.getAccountId(), requestId);
                if (prior.isPresent()) {
                    ResumeUploadOperation operation = prior.get();
                    if (!fingerprint.equals(operation.fingerprint)) {
                        throw new ApiException("IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同文件", HttpStatus.CONFLICT);
                    }
                    if (!"OK".equals(operation.resultCode) || operation.fileId == null) {
                        throw new ApiException("REQUEST_IN_PROGRESS", "相同简历上传仍在处理", HttpStatus.CONFLICT);
                    }
                    return repository.findStudentResume(operation.fileId, actor.getAccountId())
                        .orElseThrow(() -> new ApiException("NOT_FOUND", "原简历上传结果不存在", HttpStatus.NOT_FOUND));
                }
                StoredPrivateFile stored;
                try { stored = fileStorage.storeStudentResume(file); }
                catch (IOException ex) { throw new ApiException("INTERNAL_ERROR", "无法保存简历文件", HttpStatus.INTERNAL_SERVER_ERROR); }
                createdFile.set(stored);
                Long operationId = repository.insertResumeUploadOperation(actor.getAccountId(), requestId, fingerprint);
                Long fileId = repository.insertStudentResumeFile(actor.getAccountId(), stored.getFilename(), stored.getMediaType(),
                    stored.getSize(), stored.getDigest(), stored.getKey());
                StudentProfileVO current = repository.findCurrentProfile(studentId)
                    .orElseThrow(() -> new ApiException("NOT_FOUND", "学生资料不存在", HttpStatus.NOT_FOUND));
                repository.insertProfileVersion(studentId, current.getProfileVersion().intValue() + 1,
                    current.getBiography(), current.getContact(), fileId, actor.getAccountId());
                repository.insertResumeUploadAudit(actor.getAccountId(), studentId, fileId, operationId);
                repository.completeResumeUploadOperation(operationId);
                return repository.findStudentResume(fileId, actor.getAccountId())
                    .orElseThrow(() -> new ApiException("INTERNAL_ERROR", "简历上传后无法读取元数据", HttpStatus.INTERNAL_SERVER_ERROR));
            });
            // TransactionTemplate returns only after commit succeeds, so the stored blob now has a DB reference.
            createdFile.set(null);
            return result;
        } catch (RuntimeException ex) {
            fileStorage.delete(createdFile.get());
            throw ex;
        }
    }

    private static Long requireStudent(AccountPrincipal actor) {
        if (actor == null || actor.getRole() != AccountRole.STUDENT || actor.getIdentity() == null
            || actor.getIdentity().getId() == null) {
            throw new ApiException("FORBIDDEN", "仅学生本人可读取或更新学生资料", HttpStatus.FORBIDDEN);
        }
        return actor.getIdentity().getId();
    }

    public static String etag(Long studentId, int versionNo) {
        return "\"student-profile-" + studentId + "-" + versionNo + "\"";
    }

    private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) hex.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is not available", ex); }
    }
}
