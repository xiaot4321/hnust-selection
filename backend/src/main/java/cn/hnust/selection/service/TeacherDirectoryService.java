package cn.hnust.selection.service;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.TeacherDirectoryDetailVO;
import cn.hnust.selection.vo.TeacherDirectoryItemVO;

public interface TeacherDirectoryService {
    PageResult<TeacherDirectoryItemVO> list(AccountPrincipal actor, Long batchId, String keyword,
        String researchDirection, Boolean canApply, Long majorId, String degreeType, int pageNo, int pageSize);
    TeacherDirectoryDetailVO get(AccountPrincipal actor, Long batchId, Long teacherId);
}
