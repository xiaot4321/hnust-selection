package cn.hnust.selection.service;

import cn.hnust.selection.request.AdjustMatchingRelationRequest;
import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.AdminMatchingRelationVO;

public interface AdminRelationAdjustmentService {
    PageResult<AdminMatchingRelationVO> list(AccountPrincipal actor, Long batchId, int pageNo, int pageSize);
    AdminMatchingRelationVO adjust(AccountPrincipal actor, Long relationId, AdjustMatchingRelationRequest request,
        String ifMatch, String idempotencyKey);
}
