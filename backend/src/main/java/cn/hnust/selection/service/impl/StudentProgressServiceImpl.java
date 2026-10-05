package cn.hnust.selection.service.impl;

import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.StudentProgressRepository;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.StudentProgressService;
import cn.hnust.selection.vo.StudentProgressVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudentProgressServiceImpl implements StudentProgressService {
    private final StudentProgressRepository repository;

    public StudentProgressServiceImpl(StudentProgressRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public StudentProgressVO getOwnProgress(AccountPrincipal actor, Long batchId) {
        if (actor == null || actor.getRole() != AccountRole.STUDENT || actor.getAccountId() == null
            || actor.getIdentity() == null || actor.getIdentity().getId() == null) {
            throw new ApiException("FORBIDDEN", "仅学生本人可查询本人互选进度", HttpStatus.FORBIDDEN);
        }
        return repository.findProgress(actor.getIdentity().getId(), actor.getAccountId(), batchId)
            .orElseThrow(() -> new ApiException("NOT_FOUND", "未找到本人在该批次的参与记录", HttpStatus.NOT_FOUND));
    }
}
