package cn.hnust.selection.service;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.request.DecideTeacherApplicationRequest;
import cn.hnust.selection.request.BulkDecideTeacherApplicationsRequest;
import cn.hnust.selection.request.SendTeacherApplicationNoticeRequest;
import cn.hnust.selection.request.UpdateTeacherProfileRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.TeacherApplicationVO;
import cn.hnust.selection.vo.TeacherBatchSummaryVO;
import cn.hnust.selection.vo.TeacherDecisionVO;
import cn.hnust.selection.vo.TeacherNoticeVO;
import cn.hnust.selection.vo.TeacherProfileVO;
import cn.hnust.selection.vo.TeacherSupplementApplicationVO;
import cn.hnust.selection.vo.TeacherBulkDecisionAcceptedVO;
import cn.hnust.selection.vo.TeacherBulkOperationVO;

public interface TeacherWorkspaceService {
    TeacherProfileVO getProfile(AccountPrincipal actor);
    TeacherProfileVO updateProfile(AccountPrincipal actor, String ifMatch, UpdateTeacherProfileRequest request);
    PageResult<TeacherApplicationVO> roundApplications(AccountPrincipal actor, Long batchId, int roundNo, int pageNo, int pageSize);
    TeacherDecisionVO decideRoundApplication(AccountPrincipal actor, Long batchId, Long applicationId,
        DecideTeacherApplicationRequest request, String idempotencyKey);
    TeacherBulkDecisionAcceptedVO decideRoundApplicationsBulk(AccountPrincipal actor, Long batchId, int roundNo,
        BulkDecideTeacherApplicationsRequest request, String idempotencyKey);
    TeacherBulkDecisionAcceptedVO decideSupplementApplicationsBulk(AccountPrincipal actor, Long batchId,
        BulkDecideTeacherApplicationsRequest request, String idempotencyKey);
    TeacherBulkOperationVO getOperation(AccountPrincipal actor, Long operationId);
    PageResult<TeacherSupplementApplicationVO> supplementApplications(AccountPrincipal actor, Long batchId, int pageNo, int pageSize);
    TeacherDecisionVO decideSupplementApplication(AccountPrincipal actor, Long batchId, Long applicationId,
        DecideTeacherApplicationRequest request, String idempotencyKey);
    TeacherBatchSummaryVO summary(AccountPrincipal actor, Long batchId);
    TeacherNoticeVO sendNotice(AccountPrincipal actor, Long batchId,
        SendTeacherApplicationNoticeRequest request, String idempotencyKey);
}
