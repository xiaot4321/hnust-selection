package cn.hnust.selection.repository;

import cn.hnust.selection.vo.MajorOptionVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MajorDirectoryRepository {
    private final JdbcTemplate jdbcTemplate;

    public MajorDirectoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MajorOptionVO> findMajors(Long collegeId, boolean active, int pageNo, int pageSize) {
        long offset = ((long) pageNo - 1L) * pageSize;
        return jdbcTemplate.query(
            "SELECT id, major_code, name, is_active FROM major " +
                "WHERE college_id = ? AND is_active = ? ORDER BY major_code, id LIMIT ? OFFSET ?",
            (rs, rowNum) -> new MajorOptionVO(rs.getLong("id"), rs.getString("major_code"),
                rs.getString("name"), rs.getBoolean("is_active")),
            collegeId, active, pageSize, offset);
    }

    public long countMajors(Long collegeId, boolean active) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM major WHERE college_id = ? AND is_active = ?", Long.class, collegeId, active);
        return count == null ? 0L : count.longValue();
    }
}
