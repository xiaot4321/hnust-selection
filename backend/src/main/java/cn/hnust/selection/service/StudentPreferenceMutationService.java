package cn.hnust.selection.service;

import cn.hnust.selection.request.SubmitStudentPreferencesRequest;
import cn.hnust.selection.request.WithdrawStudentPreferencesRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.StudentPreferenceCommandVO;
import cn.hnust.selection.vo.StudentPreferenceWithdrawalVO;

public interface StudentPreferenceMutationService {
    StudentPreferenceCommandVO submit(AccountPrincipal actor, Long batchId,
        SubmitStudentPreferencesRequest request, String idempotencyKey);
    StudentPreferenceWithdrawalVO withdraw(AccountPrincipal actor, Long batchId,
        WithdrawStudentPreferencesRequest request, String idempotencyKey);
}
