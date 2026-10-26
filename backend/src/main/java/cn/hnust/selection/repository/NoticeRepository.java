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
            "CASE WHEN sender_account.id IS NULL THEN 'SYSTEM' ELSE sender_account.role_code END AS sender_role, " +
            "CASE WHEN sender_account.id IS NULL THEN '互选系统' " +
            "WHEN sender_account.role_code = 'TEACHER' THEN COALESCE(NULLIF(sender_teacher.full_name, ''), sender_account.login_identifier) " +
            "WHEN sender_account.role_code = 'STUDENT' THEN COALESCE(NULLIF(sender_student.full_name, ''), sender_account.login_identifier) " +
            "ELSE sender_account.login_identifier END AS sender_name, " +
            "DATE_FORMAT(recipient.delivered_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS sent_at, " +
            "DATE_FORMAT(recipient.read_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS read_at " +
            "FROM notice_recipient recipient JOIN site_notice notice ON notice.id = recipient.notice_id " +
            "LEFT JOIN business_operation sender_operation ON sender_operation.id = notice.source_operation_id " +
            "LEFT JOIN account sender_account ON sender_account.id = CASE " +
            "WHEN notice.notice_type = 'TEACHER_MESSAGE' AND notice.sender_account_id IS NULL " +
            "THEN sender_operation.actor_account_id ELSE notice.sender_account_id END " +
            "LEFT JOIN teacher sender_teacher ON sender_teacher.account_id = sender_account.id " +
            "LEFT JOIN student sender_student ON sender_student.account_id = sender_account.id " +
            "WHERE recipient.account_id = ? AND recipient.delivered_at IS NOT NULL " +
            "AND (notice.visible_at IS NULL OR notice.visible_at <= UTC_TIMESTAMP(3)) " +
            "ORDER BY recipient.delivered_at DESC, notice.id DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            StudentNoticeVO item = new StudentNoticeVO();
            item.setNoticeId(rs.getLong("notice_id"));
            item.setTitle(rs.getString("title"));
            item.setContent(rs.getString("body"));
            item.setSenderRole(rs.getString("sender_role"));
            item.setSenderName(rs.getString("sender_name"));
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
