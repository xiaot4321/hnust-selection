package cn.hnust.selection.repository;

import cn.hnust.selection.entity.TeacherOfficialProfileCacheEntity;
import cn.hnust.selection.vo.TeacherOfficialProfileVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TeacherOfficialProfileCacheRepository {
    private static final String SELECT_COLUMNS = "SELECT teacher_id, matched_full_name, matched_college_name, photo_url, " +
        "professional_title, education_level, department, teaching_level, research_directions, biography, education_experience, " +
        "work_experience, courses, research_and_achievements, profile_url, cached_at FROM teacher_official_profile_cache ";

    private final JdbcTemplate jdbc;

    public TeacherOfficialProfileCacheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TeacherOfficialProfileCacheEntity> find(Long teacherId) {
        List<TeacherOfficialProfileCacheEntity> rows = jdbc.query(SELECT_COLUMNS + "WHERE teacher_id = ?",
            this::map, teacherId);
        return rows.isEmpty() ? Optional.<TeacherOfficialProfileCacheEntity>empty() : Optional.of(rows.get(0));
    }

    public Map<Long, TeacherOfficialProfileCacheEntity> findByTeacherIds(List<Long> teacherIds) {
        if (teacherIds == null || teacherIds.isEmpty()) return Collections.emptyMap();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < teacherIds.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
        }
        List<TeacherOfficialProfileCacheEntity> rows = jdbc.query(SELECT_COLUMNS + "WHERE teacher_id IN (" +
            placeholders + ")", this::map, teacherIds.toArray());
        Map<Long, TeacherOfficialProfileCacheEntity> result = new LinkedHashMap<Long, TeacherOfficialProfileCacheEntity>();
        for (TeacherOfficialProfileCacheEntity row : rows) result.put(row.getTeacherId(), row);
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsert(Long teacherId, String fullName, String collegeName, TeacherOfficialProfileVO profile) {
        jdbc.update("INSERT INTO teacher_official_profile_cache (teacher_id, matched_full_name, matched_college_name, " +
                "photo_url, professional_title, education_level, department, teaching_level, research_directions, biography, " +
                "education_experience, work_experience, courses, research_and_achievements, profile_url, cached_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(3)) " +
                "ON DUPLICATE KEY UPDATE matched_full_name = VALUES(matched_full_name), " +
                "matched_college_name = VALUES(matched_college_name), photo_url = VALUES(photo_url), " +
                "professional_title = VALUES(professional_title), education_level = VALUES(education_level), " +
                "department = VALUES(department), teaching_level = VALUES(teaching_level), " +
                "research_directions = VALUES(research_directions), biography = VALUES(biography), " +
                "education_experience = VALUES(education_experience), work_experience = VALUES(work_experience), " +
                "courses = VALUES(courses), research_and_achievements = VALUES(research_and_achievements), " +
                "profile_url = VALUES(profile_url), cached_at = UTC_TIMESTAMP(3), row_version = row_version + 1",
            teacherId, fullName, collegeName, profile.getPhotoUrl(), profile.getProfessionalTitle(),
            profile.getEducationLevel(), profile.getDepartment(), profile.getTeachingLevel(),
            join(profile.getResearchDirections()), profile.getBiography(),
            profile.getEducationExperience(), profile.getWorkExperience(), profile.getCourses(),
            profile.getResearchAndAchievements(), profile.getProfileUrl());
    }

    private TeacherOfficialProfileCacheEntity map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        TeacherOfficialProfileCacheEntity result = new TeacherOfficialProfileCacheEntity();
        result.setTeacherId(Long.valueOf(rs.getLong("teacher_id")));
        result.setMatchedFullName(rs.getString("matched_full_name"));
        result.setMatchedCollegeName(rs.getString("matched_college_name"));
        result.setPhotoUrl(rs.getString("photo_url"));
        result.setProfessionalTitle(rs.getString("professional_title"));
        result.setEducationLevel(rs.getString("education_level"));
        result.setDepartment(rs.getString("department"));
        result.setTeachingLevel(rs.getString("teaching_level"));
        result.setResearchDirections(rs.getString("research_directions"));
        result.setBiography(rs.getString("biography"));
        result.setEducationExperience(rs.getString("education_experience"));
        result.setWorkExperience(rs.getString("work_experience"));
        result.setCourses(rs.getString("courses"));
        result.setResearchAndAchievements(rs.getString("research_and_achievements"));
        result.setProfileUrl(rs.getString("profile_url"));
        Timestamp cachedAt = rs.getTimestamp("cached_at");
        result.setCachedAt(cachedAt == null ? null : cachedAt.toLocalDateTime());
        return result;
    }

    private String join(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (result.length() > 0) result.append('\n');
            result.append(value.trim());
        }
        return result.toString();
    }
}
