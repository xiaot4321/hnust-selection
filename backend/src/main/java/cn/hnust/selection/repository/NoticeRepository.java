package cn.hnust.selection.repository;

import cn.hnust.selection.vo.StudentNoticeVO;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class NoticeRepository {
    private final JdbcTemplate jdbcTemplate;

    public NoticeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<StudentNoticeVO> findVisibleNotices(Long accountId, int pageNo, int pageSize) {
        long offset = ((long) pageNo - 1L) * pageSize;
        String sql = "SELECT notice.id AS notice_id, notice.title, notice.body, " +
            "DATE_FORMAT(recipient.delivered_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS sent_at, " +
            "DATE_FORMAT(recipient.read_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS read_at " +
            "FROM notice_recipient recipient JOIN site_notice notice ON notice.id = recipient.notice_id " +
            "WHERE recipient.account_id = ? AND recipient.delivered_at IS NOT NULL " +
            "AND (notice.visible_at IS NULL OR notice.visible_at <= UTC_TIMESTAMP(3)) " +
            "ORDER BY recipient.delivered_at DESC, notice.id DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            StudentNoticeVO item = new StudentNoticeVO();
            item.setNoticeId(rs.getLong("notice_id"));
            item.setTitle(rs.getString("title"));
            item.setContent(rs.getString("body"));
            item.setSentAt(rs.getString("sent_at"));
            item.setReadAt(rs.getString("read_at"));
            return item;
        }, accountId, pageSize, offset);
    }

    public long countVisibleNotices(Long accountId) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM notice_recipient recipient JOIN site_notice notice " +
                "ON notice.id = recipient.notice_id WHERE recipient.account_id = ? " +
                "AND recipient.delivered_at IS NOT NULL " +
                "AND (notice.visible_at IS NULL OR notice.visible_at <= UTC_TIMESTAMP(3))",
            Long.class, accountId);
        return count == null ? 0L : count.longValue();
    }

    /** 锁定并标记当前账号已投递、且已到可见时间的通知。 */
    public Optional<String> markVisibleNoticeRead(Long accountId, Long noticeId) {
        List<Long> recipientIds = jdbcTemplate.query(
            "SELECT recipient.id FROM notice_recipient recipient " +
                "JOIN site_notice notice ON notice.id = recipient.notice_id " +
                "WHERE recipient.notice_id = ? AND recipient.account_id = ? " +
                "AND recipient.delivered_at IS NOT NULL " +
                "AND (notice.visible_at IS NULL OR notice.visible_at <= UTC_TIMESTAMP(3)) FOR UPDATE",
            (rs, rowNum) -> rs.getLong("id"), noticeId, accountId);
        if (recipientIds.isEmpty()) return Optional.empty();
        Long recipientId = recipientIds.get(0);
        jdbcTemplate.update("UPDATE notice_recipient SET read_at = COALESCE(read_at, UTC_TIMESTAMP(3)) WHERE id = ?", recipientId);
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(read_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM notice_recipient WHERE id = ?",
                String.class, recipientId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }
}
