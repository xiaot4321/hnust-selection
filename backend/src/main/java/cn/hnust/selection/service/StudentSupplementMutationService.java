package cn.hnust.selection.service;

import cn.hnust.selection.request.SubmitSupplementApplicationRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.SupplementApplicationVO;

public interface StudentSupplementMutationService {
    SupplementApplicationVO submit(AccountPrincipal actor, Long batchId,
        SubmitSupplementApplicationRequest request, String idempotencyKey);
}
