package cn.hnust.selection.service;

import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.request.CreateMajorRequest;
import cn.hnust.selection.request.CreateStudentRequest;
import cn.hnust.selection.request.CreateTeacherRequest;
import cn.hnust.selection.request.SetAnnualEligibilityRequest;
import cn.hnust.selection.request.UpdateMajorRequest;
import cn.hnust.selection.vo.AcademicYearOptionVO;
import cn.hnust.selection.vo.AnnualEligibilityVO;
import cn.hnust.selection.vo.CollegeOptionVO;
import cn.hnust.selection.vo.MajorVO;
import cn.hnust.selection.vo.PageVO;
import cn.hnust.selection.vo.PersonnelCreatedVO;
import cn.hnust.selection.vo.PersonnelPersonVO;
import java.util.List;

/**
 * 学院人员、专业目录和年度资格的应用服务契约。
 *
 * <p>Controller 只处理 HTTP 与字段格式；所有学院授权、幂等、历史留存和跨表事务由实现类负责。
 * 名单模板、批次导入和导入历史由 {@link PersonnelImportService} 单独提供。</p>
 */
public interface PersonnelManagementService {
    List<CollegeOptionVO> listAuthorizedColleges(AccountPrincipal actor);

    List<MajorVO> listPublicMajors(Long collegeId);

    List<AcademicYearOptionVO> listAcademicYears(AccountPrincipal actor, Long collegeId);

    List<MajorVO> listMajors(AccountPrincipal actor, Long collegeId, Boolean activeOnly);

    MajorVO createMajor(AccountPrincipal actor, CreateMajorRequest request, String idempotencyKey);

    MajorVO updateMajor(AccountPrincipal actor, Long majorId, UpdateMajorRequest request, String idempotencyKey);

    PageVO<PersonnelPersonVO> listStudents(AccountPrincipal actor, Long collegeId, String identifier,
                                           int pageNo, int pageSize);

    PageVO<PersonnelPersonVO> listTeachers(AccountPrincipal actor, Long collegeId, String identifier,
                                           int pageNo, int pageSize);

    PersonnelCreatedVO createStudent(AccountPrincipal actor, CreateStudentRequest request, String idempotencyKey);

    PersonnelCreatedVO createTeacher(AccountPrincipal actor, CreateTeacherRequest request, String idempotencyKey);

    List<AnnualEligibilityVO> listAnnualEligibility(AccountPrincipal actor, Long collegeId,
                                                    Long academicYearId, String personType,
                                                    String identifier, boolean history);

    AnnualEligibilityVO setAnnualEligibility(AccountPrincipal actor, SetAnnualEligibilityRequest request,
                                             String idempotencyKey);
}
