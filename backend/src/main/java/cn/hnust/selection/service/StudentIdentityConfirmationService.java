package cn.hnust.selection.service;

import cn.hnust.selection.request.StudentIdentityConfirmationRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.StudentIdentityConfirmationVO;

public interface StudentIdentityConfirmationService {
    StudentIdentityConfirmationVO confirm(AccountPrincipal actor, Long batchId,
                                          StudentIdentityConfirmationRequest request, String idempotencyKey);
}
