package cn.hnust.selection.service.impl;

import cn.hnust.selection.entity.AccountEntity;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.AccountRepository;
import cn.hnust.selection.repository.PersonnelManagementRepository;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.AccountAuthorizationService;
import cn.hnust.selection.service.PersonnelAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 人员领域共用的账号刷新与学院授权校验实现。 */
@Service
public class PersonnelAccessServiceImpl implements PersonnelAccessService {
    private static final String COLLEGE_ADMIN = "COLLEGE_ADMIN";

    private final AccountRepository accountRepository;
    private final PersonnelManagementRepository personnelRepository;
    private final AccountAuthorizationService authorizationService;

    public PersonnelAccessServiceImpl(AccountRepository accountRepository,
                                      PersonnelManagementRepository personnelRepository,
                                      AccountAuthorizationService authorizationService) {
        this.accountRepository = accountRepository;
        this.personnelRepository = personnelRepository;
        this.authorizationService = authorizationService;
    }

    @Override
    public AccountPrincipal refreshActor(AccountPrincipal actor) {
        if (actor == null || actor.getAccountId() == null) {
            throw unauthenticated();
        }

        Optional<AccountEntity> found = accountRepository.findById(actor.getAccountId());
        if (!found.isPresent()) {
            throw unauthenticated();
        }

        AccountEntity account = found.get();
        if (!"ACTIVE".equalsIgnoreCase(account.getAccountStatus())) {
            throw new ApiException("SCOPE_FORBIDDEN", "账号已停用", HttpStatus.FORBIDDEN);
        }
        if (!"ADMIN".equals(account.getRoleCode())) {
            throw new ApiException("SCOPE_FORBIDDEN", "当前账号不是管理员", HttpStatus.FORBIDDEN);
        }

        try {
            return accountRepository.toPrincipal(account, actor.isTemporaryCredentialLogin());
        } catch (IllegalStateException exception) {
            throw unauthenticated();
        }
    }

    @Override
    public AccountPrincipal requireCollege(AccountPrincipal actor, Long collegeId) {
        if (collegeId == null || !personnelRepository.findCollege(collegeId).isPresent()) {
            throw new ApiException("NOT_FOUND", "学院不存在或已停用", HttpStatus.NOT_FOUND);
        }

        AccountPrincipal current = refreshActor(actor);
        authorizationService.requireCapability(current, COLLEGE_ADMIN, collegeId, null);
        return current;
    }

    private static ApiException unauthenticated() {
        return new ApiException("UNAUTHENTICATED", "登录状态已失效", HttpStatus.UNAUTHORIZED);
    }
}
