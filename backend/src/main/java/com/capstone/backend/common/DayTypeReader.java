package com.capstone.backend.common;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 적재된 인구 집계(board_population)에 있는 요일 구분 (명세 4.1 availableDayTypes).
 * /meta 응답과 인구 조회의 UNSUPPORTED_FILTER 검사가 같은 기준을 쓰도록 한곳에서 읽는다.
 */
@Component
public class DayTypeReader {

    private final JdbcTemplate jdbc;

    public DayTypeReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** ALL은 항상 포함하고, 데이터에 있는 WEEKDAY·WEEKEND를 덧붙인다. */
    public List<String> available() {
        List<String> dayTypes = new ArrayList<>(List.of("ALL"));
        dayTypes.addAll(jdbc.queryForList(
                "SELECT DISTINCT day_type FROM board_population WHERE day_type IN ('WEEKDAY', 'WEEKEND') ORDER BY day_type DESC",
                String.class));
        return dayTypes;
    }
}
