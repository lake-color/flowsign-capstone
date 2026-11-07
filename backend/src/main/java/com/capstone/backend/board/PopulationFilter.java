package com.capstone.backend.board;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import com.capstone.backend.common.ApiException;
import com.capstone.backend.common.Codes;

/**
 * 인구 조회 필터 (API 명세 4.4 쿼리). board_population·population_fact 공통 WHERE 절을 만든다.
 *
 * UNKNOWN(원본 NULL) 포함 규칙:
 *  - 성별 ALL이면 UNKNOWN 포함 (확정)
 *  - 연령·시간대는 전체를 선택했을 때만 UNKNOWN 포함 (임시 규칙, 명세 9장 미정 항목)
 *  - 주중구분 ALL이면 UNKNOWN 포함
 */
public record PopulationFilter(String gender, List<String> ageGroups, String flowType,
                               List<String> timeBands, String periodKind, String season,
                               Integer month, String dayType) {

    /** 다른 축을 고정하고 한 축 전체를 보여줄 때(차트) 그 축의 조건을 뺀다. */
    public enum Axis { NONE, GENDER, AGE, TIME, PERIOD }

    public static PopulationFilter from(Map<String, String> q) {
        String gender = oneOf(q.getOrDefault("gender", "ALL"), List.of("ALL", "MALE", "FEMALE"), "gender");
        List<String> ages = listOf(q.get("ageGroups"), Codes.AGE_GROUPS, "ageGroups");
        String flow = oneOf(q.getOrDefault("flowType", "ALL"), List.of("ALL", "STAY", "WALK", "DRIVE"), "flowType");
        List<String> times = listOf(q.get("timeBands"), Codes.TIME_BANDS, "timeBands");
        String kind = oneOf(q.getOrDefault("periodKind", "YEAR"), List.of("YEAR", "SEASON", "MONTH"), "periodKind");
        String season = null;
        Integer month = null;
        if (kind.equals("SEASON")) {
            season = oneOf(q.get("season"), List.of("SPRING", "SUMMER", "AUTUMN", "WINTER"), "season");
        } else if (kind.equals("MONTH")) {
            try {
                month = Integer.parseInt(q.getOrDefault("month", ""));
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("month는 1~12 숫자여야 합니다.");
            }
            if (month < 1 || month > 12) {
                throw ApiException.badRequest("month는 1~12 숫자여야 합니다.");
            }
        }
        String day = oneOf(q.getOrDefault("dayType", "ALL"), List.of("ALL", "WEEKDAY", "WEEKEND"), "dayType");
        return new PopulationFilter(gender, ages, flow, times, kind, season, month, day);
    }

    public static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 선택 기간에 해당하는 base_month 목록 (2025년). 겨울은 1·2·12월. */
    public List<String> months() {
        List<Integer> ms = switch (periodKind) {
            case "SEASON" -> switch (season) {
                case "SPRING" -> List.of(3, 4, 5);
                case "SUMMER" -> List.of(6, 7, 8);
                case "AUTUMN" -> List.of(9, 10, 11);
                default -> List.of(1, 2, 12);
            };
            case "MONTH" -> List.of(month);
            default -> List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        };
        return ms.stream().map(m -> String.format("%d%02d", Codes.DATA_YEAR, m)).toList();
    }

    /** 선택 유동 유형의 값 SQL 식. ALL은 체류+보행+주행. */
    public String flowExpression() {
        return switch (flowType) {
            case "STAY" -> "SUM(stay)";
            case "WALK" -> "SUM(walk)";
            case "DRIVE" -> "SUM(drive)";
            // 세 값이 모두 NULL이면 0이 아니라 NULL
            default -> "(CASE WHEN COUNT(stay) + COUNT(walk) + COUNT(drive) = 0 THEN NULL"
                    + " ELSE COALESCE(SUM(stay), 0) + COALESCE(SUM(walk), 0) + COALESCE(SUM(drive), 0) END)";
        };
    }

    /** WHERE 절 조건들(앞에 AND를 붙여 쓴다). skip 축의 조건은 넣지 않는다. */
    public String where(Axis skip, MapSqlParameterSource params) {
        List<String> parts = new ArrayList<>();
        if (skip != Axis.PERIOD) {
            parts.add("base_month IN (:months)");
            params.addValue("months", months());
        }
        if (skip != Axis.GENDER && !gender.equals("ALL")) {
            parts.add("gender = :gender");
            params.addValue("gender", gender);
        }
        if (skip != Axis.AGE && ageGroups.size() < Codes.AGE_GROUPS.size()) {
            parts.add("age_group IN (:ages)");
            params.addValue("ages", ageGroups);
        }
        if (skip != Axis.TIME && timeBands.size() < Codes.TIME_BANDS.size()) {
            parts.add("time_slot IN (:times)");
            params.addValue("times", timeBands);
        }
        if (!dayType.equals("ALL")) {
            parts.add("day_type = :dayType");
            params.addValue("dayType", dayType);
        }
        return parts.isEmpty() ? "" : " AND " + String.join(" AND ", parts);
    }

    /** filterApplied 응답 형태 */
    public Map<String, Object> applied() {
        Map<String, Object> period = new java.util.LinkedHashMap<>();
        period.put("kind", periodKind);
        period.put("year", Codes.DATA_YEAR);
        if (season != null) {
            period.put("season", season);
        }
        if (month != null) {
            period.put("month", month);
        }
        return Map.of("gender", gender, "ageGroups", ageGroups, "flowType", flowType,
                "timeBands", timeBands, "period", period, "dayType", dayType);
    }

    private static String oneOf(String value, List<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw ApiException.badRequest(field + " 값이 올바르지 않습니다: " + value);
        }
        return value;
    }

    private static List<String> listOf(String value, List<String> allowed, String field) {
        List<String> items = csv(value);
        if (items.isEmpty()) {
            return allowed;
        }
        for (String item : items) {
            if (!allowed.contains(item)) {
                throw ApiException.badRequest(field + " 값이 올바르지 않습니다: " + item);
            }
        }
        return allowed.stream().filter(items::contains).toList();
    }
}
