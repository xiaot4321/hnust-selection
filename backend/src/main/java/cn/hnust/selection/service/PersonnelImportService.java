package cn.hnust.selection.service;

import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.PersonnelImportRowVO;
import cn.hnust.selection.vo.PersonnelImportVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 名单导入应用服务契约。
 *
 * <p>模板下载、逐行导入和导入历史读取作为独立用例组织；账号创建仍委托人员注册服务，
 * 每行处理结果和整次导入审计由实现类编排。</p>
 */
public interface PersonnelImportService {
    /** 生成指定人员类型的固定 CSV 表头。 */
    String csvTemplate(AccountPrincipal actor, Long collegeId, String personType);

    /** 上传并逐行处理名单；首次响应可返回新账号的一次性临时凭证。 */
    PersonnelImportVO importPersonnel(AccountPrincipal actor, Long collegeId, Long academicYearId,
                                      String personType, MultipartFile file, String idempotencyKey);

    /** 查询导入任务概要，不会重新返回已经展示过的明文凭证。 */
    PersonnelImportVO getImport(AccountPrincipal actor, Long importId);

    /** 查询导入逐行结果，不会重新返回已经展示过的明文凭证。 */
    List<PersonnelImportRowVO> getImportRows(AccountPrincipal actor, Long importId);
}
