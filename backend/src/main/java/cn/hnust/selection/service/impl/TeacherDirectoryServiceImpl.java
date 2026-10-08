package cn.hnust.selection.service.impl;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.enums.AccountRole;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.TeacherDirectoryRepository;
import cn.hnust.selection.repository.TeacherDirectoryRepository.DirectoryContext;
import cn.hnust.selection.repository.TeacherDirectoryRepository.TeacherRow;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.TeacherDirectoryService;
import cn.hnust.selection.vo.AllowedMajorVO;
import cn.hnust.selection.vo.TeacherDirectoryDetailVO;
import cn.hnust.selection.vo.TeacherDirectoryItemVO;
import cn.hnust.selection.vo.TeacherOfficialProfileVO;
import cn.hnust.selection.entity.TeacherOfficialProfileCacheEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class TeacherDirectoryServiceImpl implements TeacherDirectoryService {
    private final TeacherDirectoryRepository repository;
    private final OfficialFacultyProfileLookupService officialProfileLookup;

    public TeacherDirectoryServiceImpl(TeacherDirectoryRepository repository,
        OfficialFacultyProfileLookupService officialProfileLookup) {
        this.repository = repository;
        this.officialProfileLookup = officialProfileLookup;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<TeacherDirectoryItemVO> list(AccountPrincipal actor, Long batchId, String keyword,
        String researchDirection, Boolean canApply, Long majorId, String degreeType, int pageNo, int pageSize) {
        Long studentId = requireStudent(actor);
        validatePositive(batchId, "batchId");
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) throw invalidArgument("分页参数超出范围");
        if (majorId != null) validatePositive(majorId, "majorId");
        String normalizedKeyword = normalizeFilter(keyword, "keyword");
        String normalizedDirection = normalizeFilter(researchDirection, "researchDirection");
        Integer degreeBit = degreeBit(degreeType);
        requireContext(batchId, studentId);

        List<TeacherRow> rows = repository.findTeachers(batchId, studentId, normalizedKeyword,
            normalizedDirection, majorId, degreeBit, canApply, pageNo, pageSize);
        List<Long> scopeIds = new ArrayList<Long>();
        List<Long> teacherIds = new ArrayList<Long>();
        for (TeacherRow row : rows) {
            scopeIds.add(row.getScopeVersionId());
            teacherIds.add(row.getTeacherId());
        }
        Map<Long, List<AllowedMajorVO>> majorsByScope = repository.findAllowedMajors(scopeIds);
        Map<Long, TeacherOfficialProfileCacheEntity> officialByTeacher = officialProfileLookup.cachedProfiles(teacherIds);
        List<TeacherDirectoryItemVO> items = new ArrayList<TeacherDirectoryItemVO>();
        for (TeacherRow row : rows) {
            TeacherOfficialProfileVO official = officialProfileLookup.cachedProfile(officialByTeacher.get(row.getTeacherId()),
                row.getFullName(), row.getCollegeName());
            items.add(toItem(row, majorsByScope.get(row.getScopeVersionId()), official));
        }
        long total = repository.countTeachers(batchId, studentId, normalizedKeyword,
            normalizedDirection, majorId, degreeBit, canApply);
        return new PageResult<TeacherDirectoryItemVO>(items, total, pageNo, pageSize);
    }

    @Override
    public TeacherDirectoryDetailVO get(AccountPrincipal actor, Long batchId, Long teacherId) {
        Long studentId = requireStudent(actor);
        validatePositive(batchId, "batchId");
        validatePositive(teacherId, "teacherId");
        requireContext(batchId, studentId);
        TeacherRow row = repository.findTeacher(batchId, studentId, teacherId)
            .orElseThrow(() -> notFound("未找到该批次可查看的导师资料"));
        TeacherOfficialProfileVO official = officialProfileLookup.getOrRefresh(row.getTeacherId(), row.getFullName(), row.getCollegeName());
        TeacherDirectoryDetailVO result = new TeacherDirectoryDetailVO();
        copy(toItem(row, repository.findAllowedMajors(row.getScopeVersionId()), official), result);
        result.setBiography(firstNonBlank(row.getBiography(), official == null ? null : official.getBiography()));
        return result;
    }

    private DirectoryContext requireContext(Long batchId, Long studentId) {
        DirectoryContext context = repository.findContext(batchId, studentId)
            .orElseThrow(() -> notFound("未找到该批次或本人无权查看"));
        if (!"ACTIVE".equals(context.getAccountStatus())) {
            throw new ApiException("ACCOUNT_DISABLED", "学生账号当前不可用", HttpStatus.FORBIDDEN);
        }
        if (!"ACADEMIC_MASTER".equals(context.getDegreeType())
            && !"PROFESSIONAL_MASTER".equals(context.getDegreeType())) {
            throw new ApiException("STATE_CONFLICT", "学生学位类型无效", HttpStatus.CONFLICT);
        }
        return context;
    }

    private TeacherDirectoryItemVO toItem(TeacherRow row, List<AllowedMajorVO> allowedMajors,
        TeacherOfficialProfileVO official) {
        TeacherDirectoryItemVO result = new TeacherDirectoryItemVO();
        result.setTeacherId(row.getTeacherId());
        result.setEmployeeNo(row.getEmployeeNo());
        result.setDisplayName(row.getFullName());
        List<String> directions = splitDirections(row.getResearchDirections());
        if (directions.isEmpty() && official != null && official.getResearchDirections() != null) {
            directions = official.getResearchDirections();
        }
        result.setResearchDirections(directions);
        result.setProfileSummary(firstNonBlank(row.getBiography(), official == null ? null : official.getBiography()));
        result.setAllowedDegreeTypes(degreeTypes(row.getDegreeMask().intValue()));
        result.setAllowedMajors(allowedMajors == null ? Collections.<AllowedMajorVO>emptyList() : allowedMajors);
        result.setCanApply(row.isCanApply());
        result.setOfficialProfile(official);
        return result;
    }

    private void copy(TeacherDirectoryItemVO source, TeacherDirectoryDetailVO target) {
        target.setTeacherId(source.getTeacherId());
        target.setEmployeeNo(source.getEmployeeNo());
        target.setDisplayName(source.getDisplayName());
        target.setResearchDirections(source.getResearchDirections());
        target.setProfileSummary(source.getProfileSummary());
        target.setAllowedDegreeTypes(source.getAllowedDegreeTypes());
        target.setAllowedMajors(source.getAllowedMajors());
        target.setCanApply(source.isCanApply());
        target.setOfficialProfile(source.getOfficialProfile());
    }

    private List<String> splitDirections(String value) {
        if (value == null || value.trim().isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        for (String part : value.split("[\\r\\n]+")) {
            String normalized = part.trim();
            if (!normalized.isEmpty() && !result.contains(normalized)) result.add(normalized);
        }
        return result;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.trim().isEmpty() ? (fallback == null ? "" : fallback) : preferred;
    }

    private List<String> degreeTypes(int mask) {
        List<String> result = new ArrayList<String>();
        if ((mask & 1) != 0) result.add("ACADEMIC_MASTER");
        if ((mask & 2) != 0) result.add("PROFESSIONAL_MASTER");
        return result;
    }

    private Integer degreeBit(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("ACADEMIC_MASTER".equals(normalized)) return Integer.valueOf(1);
        if ("PROFESSIONAL_MASTER".equals(normalized)) return Integer.valueOf(2);
        throw invalidArgument("degreeType 必须为 ACADEMIC_MASTER 或 PROFESSIONAL_MASTER");
    }

    private String normalizeFilter(String value, String field) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > 128) throw invalidArgument(field + " 长度不能超过 128 个字符");
        return normalized;
    }

    private void validatePositive(Long value, String field) {
        if (value == null || value.longValue() <= 0) throw invalidArgument(field + " 必须为正整数");
    }

    private static Long requireStudent(AccountPrincipal actor) {
        if (actor == null || actor.getRole() != AccountRole.STUDENT || actor.getAccountId() == null
            || actor.getIdentity() == null || actor.getIdentity().getId() == null) {
            throw new ApiException("FORBIDDEN", "仅学生可查看导师目录", HttpStatus.FORBIDDEN);
        }
        return actor.getIdentity().getId();
    }

    private static ApiException invalidArgument(String message) {
        return new ApiException("INVALID_ARGUMENT", message, HttpStatus.BAD_REQUEST);
    }

    private static ApiException notFound(String message) {
        return new ApiException("NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }
}
