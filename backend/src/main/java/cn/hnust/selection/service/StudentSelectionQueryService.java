package cn.hnust.selection.service;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.PreferenceSubmissionVO;
import cn.hnust.selection.vo.StudentPreferencesVO;
import cn.hnust.selection.vo.SupplementApplicationVO;

public interface StudentSelectionQueryService {
    StudentPreferencesVO getCurrentPreferences(AccountPrincipal actor, Long batchId);
    PageResult<PreferenceSubmissionVO> listPreferenceSubmissions(AccountPrincipal actor, Long batchId,
                                                                  int pageNo, int pageSize);
    PageResult<SupplementApplicationVO> listSupplementApplications(AccountPrincipal actor, Long batchId,
                                                                     int pageNo, int pageSize);
}
