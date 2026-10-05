package cn.hnust.selection.service;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.request.CreateStudentIdentityCorrectionRequest;
import cn.hnust.selection.request.ReviewIdentityCorrectionRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.AdminIdentityCorrectionVO;
import cn.hnust.selection.vo.StudentIdentityCorrectionCommandVO;
import cn.hnust.selection.vo.StudentIdentityCorrectionVO;

public interface StudentIdentityCorrectionService {
    PageResult<StudentIdentityCorrectionVO> listOwnRequests(AccountPrincipal actor, int pageNo, int pageSize);
    StudentIdentityCorrectionCommandVO createRequest(AccountPrincipal actor,
        CreateStudentIdentityCorrectionRequest request, String idempotencyKey);
    PageResult<AdminIdentityCorrectionVO> listAdminRequests(AccountPrincipal actor, Long collegeId,
        String status, int pageNo, int pageSize);
    StudentIdentityCorrectionCommandVO decideAdminRequest(AccountPrincipal actor, Long requestId,
        ReviewIdentityCorrectionRequest request, String idempotencyKey);
}
