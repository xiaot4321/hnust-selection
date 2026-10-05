package cn.hnust.selection.service.impl;

import cn.hnust.selection.common.PageResult;
import cn.hnust.selection.exception.ApiException;
import cn.hnust.selection.repository.NoticeRepository;
import cn.hnust.selection.security.AccountPrincipal;
import cn.hnust.selection.service.NoticeService;
import cn.hnust.selection.vo.StudentNoticeVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoticeServiceImpl implements NoticeService {
    private final NoticeRepository repository;

    public NoticeServiceImpl(NoticeRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<StudentNoticeVO> listOwnNotices(AccountPrincipal actor, int pageNo, int pageSize) {
        Long accountId = requireAccount(actor);
        return new PageResult<StudentNoticeVO>(repository.findVisibleNotices(accountId, pageNo, pageSize),
            repository.countVisibleNotices(accountId), pageNo, pageSize);
    }

    @Override
    @Transactional
    public String markOwnNoticeRead(AccountPrincipal actor, Long noticeId) {
        String readAt = repository.markVisibleNoticeRead(requireAccount(actor), noticeId)
            .orElseThrow(() -> new ApiException("NOT_FOUND", "未找到本人已投递的可见通知", HttpStatus.NOT_FOUND));
        return readAt;
    }

    private static Long requireAccount(AccountPrincipal actor) {
        if (actor == null || actor.getAccountId() == null) {
            throw new ApiException("UNAUTHENTICATED", "请先登录", HttpStatus.UNAUTHORIZED);
        }
        return actor.getAccountId();
    }
}
