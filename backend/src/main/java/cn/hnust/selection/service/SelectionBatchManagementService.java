package cn.hnust.selection.service;

import cn.hnust.selection.request.BatchScheduleRequest;
import cn.hnust.selection.request.CreateSelectionBatchRequest;
import cn.hnust.selection.request.ExtendRoundRequest;
import cn.hnust.selection.request.ReopenRoundRequest;
import cn.hnust.selection.request.SetTeacherApplicationScopeRequest;
import cn.hnust.selection.request.SetSupplementTeachersRequest;
import cn.hnust.selection.request.SetTeacherQuotaRequest;
import cn.hnust.selection.request.UpdateSelectionBatchRequest;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.AcademicYearOptionVO;
import cn.hnust.selection.vo.BatchCollegeOptionVO;
import cn.hnust.selection.vo.BatchTeacherQuotaVO;
import cn.hnust.selection.vo.BatchStatisticsVO;
import cn.hnust.selection.vo.CollegeOptionVO;
import cn.hnust.selection.vo.MajorVO;
import cn.hnust.selection.vo.SelectionBatchDetailVO;
import cn.hnust.selection.vo.SelectionBatchSummaryVO;
import cn.hnust.selection.vo.TeacherApplicationScopeVO;
import cn.hnust.selection.vo.TeacherScopeBatchOptionVO;
import cn.hnust.selection.vo.SupplementTeacherVO;
import java.util.List;

/**
 * 批次配置、名额和导师可报范围应用服务契约。
 *
 * <p>Controller 只负责 HTTP 参数和响应头解析；本服务负责刷新登录主体、验证管理员能力及其学院/批次范围，
 * 并在事务内编排状态检查、行锁、幂等记录、版本快照和审计。导师范围接口始终以认证主体对应的导师身份为准，
 * 调用方不能通过请求体指定其他导师。</p>
 */
public interface SelectionBatchManagementService {
    /** 返回当前管理员可见的学院；仅学院级 BATCH_MANAGER 授权允许在该学院新建批次。 */
    List<BatchCollegeOptionVO> listAuthorizedColleges(AccountPrincipal actor);

    /** 校验学院范围后返回可选择学年；批次级授权只用来读取配置目录，不能借此创建批次。 */
    List<AcademicYearOptionVO> listAcademicYears(AccountPrincipal actor, Long collegeId);

    /** 返回学院授权下的全部批次，或批次级授权明确允许访问的批次子集。 */
    List<SelectionBatchSummaryVO> listBatches(AccountPrincipal actor, Long collegeId);

    /** 读取批次详情前验证 BATCH_MANAGER 范围；未授权和不存在均按不可见资源处理。 */
    SelectionBatchDetailVO getBatch(AccountPrincipal actor, Long batchId);

    /** 创建草稿；创建命令需要 UUID v4 幂等键，学年行锁串行化追加批次判断。 */
    SelectionBatchDetailVO createBatch(AccountPrincipal actor, CreateSelectionBatchRequest request, String idempotencyKey);

    /** 仅更新草稿元数据；expectedVersion 来自强 If-Match 标签。 */
    SelectionBatchDetailVO updateBatch(AccountPrincipal actor, Long batchId,
        UpdateSelectionBatchRequest request, long expectedVersion);

    /** 完整替换排期；阶段时间顺序和运行中阶段可调整范围由服务端校验。 */
    SelectionBatchDetailVO saveSchedule(AccountPrincipal actor, Long batchId,
        BatchScheduleRequest request, long expectedVersion);

    /** 发布草稿并原子占用学院/学年运行槽位，创建规则快照和审计记录。 */
    SelectionBatchDetailVO publish(AccountPrincipal actor, Long batchId, String idempotencyKey);

    /** 启动已发布批次；若填报窗口此刻开放，会在同一事务冻结名单与导师范围。 */
    SelectionBatchDetailVO start(AccountPrincipal actor, Long batchId, String idempotencyKey);

    /** 暂停活动批次；后续恢复时按数据库暂停时长顺延当前及未开始阶段。 */
    SelectionBatchDetailVO pause(AccountPrincipal actor, Long batchId, String idempotencyKey);

    /** 恢复暂停批次，并平移仍有效的阶段与补选窗口时间。 */
    SelectionBatchDetailVO resume(AccountPrincipal actor, Long batchId, String idempotencyKey);

    /** 取消尚未结束的批次，结案待处理申请并将未匹配学生记为 UNMATCHED。 */
    SelectionBatchDetailVO cancel(AccountPrincipal actor, Long batchId, String idempotencyKey);

    /** 归档已完成批次。 */
    SelectionBatchDetailVO archive(AccountPrincipal actor, Long batchId, String idempotencyKey);

    /** 解除归档回到已完成状态，供授权的后续审计纠错使用。 */
    SelectionBatchDetailVO unarchive(AccountPrincipal actor, Long batchId, String idempotencyKey);

    /** 在轮次截止前延长该轮，并将后续阶段按截止时间差整体顺延。 */
    SelectionBatchDetailVO extendRound(AccountPrincipal actor, Long batchId, int roundNo,
        ExtendRoundRequest request, long expectedVersion, String idempotencyKey);

    /** 重开已按截止时间结案的轮次，恢复可恢复申请并按新截止时间重排后续阶段。 */
    SelectionBatchDetailVO reopenRound(AccountPrincipal actor, Long batchId, int roundNo,
        ReopenRoundRequest request, long expectedVersion, String idempotencyKey);

    /** 读取补选导师许可及名额摘要。 */
    List<SupplementTeacherVO> listSupplementTeachers(AccountPrincipal actor, Long batchId);

    /** 完整替换补选导师许可名单；已提交申请不受撤销新许可影响。 */
    List<SupplementTeacherVO> setSupplementTeachers(AccountPrincipal actor, Long batchId,
        SetSupplementTeachersRequest request, long expectedVersion, String idempotencyKey);

    /** 返回本批次所在学院当前启用的专业目录，供批次配置页面展示。 */
    List<MajorVO> listBatchMajors(AccountPrincipal actor, Long batchId);

    /** 返回当前有效导师目录、名额占用以及范围配置/冻结状态。 */
    List<BatchTeacherQuotaVO> listTeacherQuotas(AccountPrincipal actor, Long batchId);

    /** 返回授权批次的冻结分母、未匹配来源、逐轮结果、补选和名额统计。 */
    BatchStatisticsVO statistics(AccountPrincipal actor, Long batchId);

    /** 设置导师名额上限；按行版本防止覆盖并发更新，并校验新上限不低于已占用数。 */
    BatchTeacherQuotaVO setTeacherQuota(AccountPrincipal actor, Long batchId, Long teacherId,
        SetTeacherQuotaRequest request, long expectedVersion, String idempotencyKey);

    /** 返回当前导师名额关联的批次选项，不查询其他导师的批次或配置。 */
    List<TeacherScopeBatchOptionVO> listTeacherScopeBatches(AccountPrincipal actor);

    /** 返回本人在指定批次的当前范围；未配置时返回规则规定的“全部”预览值。 */
    TeacherApplicationScopeVO getTeacherScope(AccountPrincipal actor, Long batchId);

    /** 完整替换本人范围并追加新版本；填报开始或范围槽位冻结后拒绝修改。 */
    TeacherApplicationScopeVO setTeacherScope(AccountPrincipal actor, Long batchId,
        SetTeacherApplicationScopeRequest request, int expectedVersion);

    /** 定时扫描已启动且到达填报开始时刻的批次，逐批次事务化开窗和冻结。 */
    void openDueFillingWindows();
}
