package cn.hnust.selection.service;

import cn.hnust.selection.request.CreateAdminAccountRequest;
import cn.hnust.selection.vo.AdminAccountCredentialVO;
import cn.hnust.selection.vo.AdminAccountDirectoryItemVO;
import cn.hnust.selection.vo.PageVO;
import cn.hnust.selection.security.AccountPrincipal;

/** 总管理员创建普通管理员账号并重置其一次性临时凭证的用例接口。 */
public interface AdminAccountLifecycleService {
    PageVO<AdminAccountDirectoryItemVO> listAccounts(AccountPrincipal actor, int pageNo, int pageSize);

    AdminAccountCredentialVO create(AccountPrincipal actor, CreateAdminAccountRequest request,
                                          String idempotencyKey);

    AdminAccountCredentialVO resetTemporaryCredential(AccountPrincipal actor, Long targetAccountId,
                                                             String idempotencyKey);
}
