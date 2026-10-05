package cn.hnust.selection.service;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.StudentBatchSummaryVO;

public interface StudentBatchService {
    PageResult<StudentBatchSummaryVO> listOwnBatches(AccountPrincipal actor, int pageNo, int pageSize);
}
