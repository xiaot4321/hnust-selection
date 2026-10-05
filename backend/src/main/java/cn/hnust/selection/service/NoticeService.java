package cn.hnust.selection.service;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.vo.StudentNoticeVO;

public interface NoticeService {
    PageResult<StudentNoticeVO> listOwnNotices(AccountPrincipal actor, int pageNo, int pageSize);
    String markOwnNoticeRead(AccountPrincipal actor, Long noticeId);
}
