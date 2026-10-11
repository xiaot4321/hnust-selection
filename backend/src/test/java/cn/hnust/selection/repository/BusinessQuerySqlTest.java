package cn.hnust.selection.repository;

import cn.hnust.selection.vo.StudentProgressVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class BusinessQuerySqlTest {

    @Test
    @SuppressWarnings("unchecked")
    void studentProgressRoundsQueryJoinsBatchForCollegeScope() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("batch.batch_status")) {
                return Collections.singletonList(new StudentProgressVO());
            }
            return Collections.emptyList();
        });

        new StudentProgressRepository(jdbc).findProgress(12L, 34L, 56L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sqlCaptor.capture(), any(RowMapper.class), any(), any(), any());
        String roundsSql = sqlCaptor.getAllValues().get(1).toUpperCase(Locale.ROOT);
        assertTrue(roundsSql.contains("JOIN SELECTION_BATCH BATCH ON BATCH.ID = BATCH_STUDENT.BATCH_ID"));
        assertTrue(roundsSql.contains("BATCH.COLLEGE_ID = STUDENT.COLLEGE_ID"));
    }

    @Test
    void teacherSupplementAccessKeepsOuterCorrelationsOutOfNestedJoinCondition() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(Integer.valueOf(1));

        assertTrue(new TeacherWorkspaceRepository(jdbc).hasSupplementWorkAccess(7L, 19L));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sqlCaptor.capture(), eq(Integer.class), eq(19L), eq(7L));
        String sql = sqlCaptor.getValue().toUpperCase(Locale.ROOT);
        int historicJoin = sql.indexOf("JOIN ANNUAL_ELIGIBILITY HISTORIC");
        int historicWhere = sql.indexOf("WHERE APPLICATION.SUPPLEMENT_WINDOW_ID", historicJoin);
        assertTrue(historicJoin >= 0);
        assertTrue(historicWhere > historicJoin);

        String historicJoinClause = sql.substring(historicJoin, historicWhere);
        assertFalse(historicJoinClause.contains("BATCH."));
        assertFalse(historicJoinClause.contains("QUOTA."));
        String historicWhereClause = sql.substring(historicWhere);
        assertTrue(historicWhereClause.contains("HISTORIC.ACADEMIC_YEAR_ID = BATCH.ACADEMIC_YEAR_ID"));
        assertTrue(historicWhereClause.contains("HISTORIC.TEACHER_ID = QUOTA.TEACHER_ID"));
    }
}
