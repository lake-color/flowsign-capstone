package com.capstone.backend.board;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.capstone.backend.board.PopulationFilter.Axis;
import com.capstone.backend.common.Codes;
import com.capstone.backend.common.DatasetInfoReader;
import com.capstone.backend.common.PopulationValue;

/**
 * 게시대 3×3 영향권 인구 조회 (API 명세 4.4, 4.5).
 *
 * 인구값은 선택 기간·요일 구분의 일평균(명/일)이다 (팀 결정 2026-11-16, {@link DayCount}).
 * 원본 월 값이 합계이므로(명세 9장 L3) 합계를 구한 뒤 해당 일수로 나눈다. 월별 차트는 그 달의 일수로 나눈다.
 * 마스킹·공개 가능 여부(L5)가 확인되지 않아 status는 UNVERIFIED로 둔다.
 */
@Service
public class BoardPopulationService {

    private final NamedParameterJdbcTemplate jdbc;
    /** 응답 안내 문구가 DB에 적재된 세트 종류(실제/가짜)를 따르도록 요청마다 읽는다. */
    private final DatasetInfoReader dataset;

    public BoardPopulationService(NamedParameterJdbcTemplate jdbc, DatasetInfoReader dataset) {
        this.jdbc = jdbc;
        this.dataset = dataset;
    }

    public Map<String, Object> population(long boardId, PopulationFilter f) {
        int days = f.days();
        Map<String, Object> totals = totals(boardId, f);
        Double stay = DayCount.perDay((Double) totals.get("stay"), days);
        Double walk = DayCount.perDay((Double) totals.get("walk"), days);
        Double drive = DayCount.perDay((Double) totals.get("drive"), days);

        Map<String, Double> hourly = perDay(groupBy(boardId, f, "time_slot", Axis.TIME), days);
        Map<String, Double> monthly = groupBy(boardId, f, "base_month", Axis.PERIOD);
        List<String> selectedMonths = f.months();

        int expected = count("SELECT COUNT(*) FROM board_cell WHERE board_id = :id", boardId);
        MapSqlParameterSource p = new MapSqlParameterSource("id", boardId);
        Integer matched = jdbc.queryForObject(
                "SELECT COALESCE(MAX(cell_count), 0) FROM board_population WHERE board_id = :id" + f.where(Axis.NONE, p),
                p, Integer.class);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("boardId", String.valueOf(boardId));
        data.put("centerCellId", centerCellId(boardId));
        data.put("filterApplied", f.applied());
        data.put("population", Map.of(
                "totalFlow", PopulationValue.of(sumOrNull(stay, walk, drive)),
                "stay", PopulationValue.of(stay),
                "walk", PopulationValue.of(walk),
                "drive", PopulationValue.of(drive)));
        data.put("hourly", Codes.TIME_BANDS.stream().map(t -> Map.of(
                "timeBand", t, "value", PopulationValue.of(hourly.get(t)),
                "selected", f.timeBands().contains(t))).toList());
        data.put("monthly", java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(m -> {
            String ym = String.format("%d%02d", Codes.DATA_YEAR, m);
            Double monthAverage = DayCount.perDay(monthly.get(ym), DayCount.of(List.of(ym), f.dayType()));
            return Map.of("month", m, "value", PopulationValue.of(monthAverage),
                    "selected", selectedMonths.contains(ym));
        }).toList());
        data.put("genderDistribution", distribution(perDay(groupBy(boardId, f, "gender", Axis.GENDER), days),
                List.of("MALE", "FEMALE", "UNKNOWN")));
        data.put("ageDistribution", distribution(perDay(groupBy(boardId, f, "age_group", Axis.AGE), days),
                concat(Codes.AGE_GROUPS, "UNKNOWN")));
        data.put("flowDistribution", distribution(mapOf("STAY", stay, "WALK", walk, "DRIVE", drive),
                List.of("STAY", "WALK", "DRIVE")));
        data.put("servicePopulation", distribution(mapOf(
                        "RESIDENCE", DayCount.perDay((Double) totals.get("residence"), days),
                        "WORK", DayCount.perDay((Double) totals.get("work"), days),
                        "VISIT", DayCount.perDay((Double) totals.get("visit"), days)),
                List.of("RESIDENCE", "WORK", "VISIT")));
        data.put("expectedCellCount", expected);
        data.put("matchedCellCount", matched);
        data.put("aggregationStatus", matched < expected ? "PARTIAL" : "UNVERIFIED");
        data.put("dataNotice", dataset.current().populationNotice());
        return data;
    }

    public Map<String, Object> cells(BoardRepository.Board board, PopulationFilter f) {
        long boardId = Long.parseLong(board.boardId());
        int days = f.days();
        MapSqlParameterSource p = new MapSqlParameterSource("id", boardId);
        String sql = """
                SELECT bc.cell_id, bc.is_center, bc.distance_meters, c.latitude, c.longitude, pf.value
                FROM board_cell bc
                JOIN cell c ON c.cell_id = bc.cell_id
                LEFT JOIN (
                    SELECT cell_id, %s AS value FROM population_fact
                    WHERE cell_id IN (SELECT cell_id FROM board_cell WHERE board_id = :id) %s
                    GROUP BY cell_id
                ) pf ON pf.cell_id = bc.cell_id
                WHERE bc.board_id = :id
                ORDER BY bc.is_center DESC, c.latitude DESC, c.longitude
                """.formatted(f.flowExpression(), f.where(Axis.NONE, p));

        List<Map<String, Object>> cells = jdbc.query(sql, p, (rs, i) -> {
            double lat = rs.getDouble("latitude");
            double lng = rs.getDouble("longitude");
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("cellId", rs.getString("cell_id"));
            cell.put("isCenter", rs.getBoolean("is_center"));
            cell.put("distanceMeters", rs.getDouble("distance_meters"));
            cell.put("center", Map.of("latitude", lat, "longitude", lng));
            cell.put("geometry", square(lat, lng));
            cell.put("geometryStatus", "AVAILABLE");
            cell.put("population", PopulationValue.of(DayCount.perDay(rs.getObject("value", Double.class), days)));
            return cell;
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("boardId", board.boardId());
        data.put("centerCellId", centerCellId(boardId));
        data.put("cellSizeMeters", 250);
        data.put("center", Map.of("latitude", board.latitude(), "longitude", board.longitude()));
        data.put("cells", cells);
        data.put("cellCount", cells.size());
        data.put("dataNotice", dataset.current().populationNotice());
        return data;
    }

    /** Cell 중심에서 사각형 변까지의 거리(m). 250m Cell의 반. */
    static final double CELL_HALF_METERS = 125.0;
    static final double METERS_PER_DEG_LAT = 111_320.0;

    /**
     * Cell 중심 ±125m 정사각형 GeoJSON Polygon. 좌표 순서는 [경도, 위도].
     *
     * Cell 데이터에는 중심 좌표만 있으므로, 변이 정남북·정동서인 근사 사각형을 그린다.
     * 격자 검증(grid_spec.status)과 관계없이 항상 반환한다.
     */
    static Map<String, Object> square(double lat, double lng) {
        double dLat = CELL_HALF_METERS / METERS_PER_DEG_LAT;
        double dLng = CELL_HALF_METERS / (METERS_PER_DEG_LAT * Math.cos(Math.toRadians(lat)));
        double s = lat - dLat, n = lat + dLat, w = lng - dLng, e = lng + dLng;
        return Map.of("type", "Polygon", "coordinates",
                List.of(List.of(List.of(w, s), List.of(e, s), List.of(e, n), List.of(w, n), List.of(w, s))));
    }

    private Map<String, Object> totals(long boardId, PopulationFilter f) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", boardId);
        return jdbc.queryForMap("""
                SELECT SUM(stay) AS stay, SUM(walk) AS walk, SUM(drive) AS drive,
                       SUM(residence) AS residence, SUM(work) AS work, SUM(visit) AS visit
                FROM board_population WHERE board_id = :id
                """ + f.where(Axis.NONE, p), p);
    }

    private Map<String, Double> groupBy(long boardId, PopulationFilter f, String column, Axis axis) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", boardId);
        String sql = "SELECT " + column + " AS k, " + f.flowExpression() + " AS v FROM board_population"
                + " WHERE board_id = :id" + f.where(axis, p) + " GROUP BY " + column;
        Map<String, Double> result = new LinkedHashMap<>();
        jdbc.query(sql, p, rs -> {
            result.put(rs.getString("k"), rs.getObject("v", Double.class));
        });
        return result;
    }

    private static Map<String, Double> perDay(Map<String, Double> sums, int days) {
        Map<String, Double> result = new LinkedHashMap<>();
        sums.forEach((k, v) -> result.put(k, DayCount.perDay(v, days)));
        return result;
    }

    private static List<Map<String, Object>> distribution(Map<String, Double> values, List<String> keys) {
        double total = keys.stream().map(values::get).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
        List<Map<String, Object>> items = new ArrayList<>();
        for (String key : keys) {
            Double v = values.get(key);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("population", PopulationValue.of(v));
            item.put("ratio", v == null || total == 0 ? null : Math.round(v / total * 1000) / 1000.0);
            items.add(item);
        }
        return items;
    }

    private String centerCellId(long boardId) {
        List<String> ids = jdbc.queryForList("SELECT cell_id FROM board_cell WHERE board_id = :id AND is_center",
                new MapSqlParameterSource("id", boardId), String.class);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private int count(String sql, long boardId) {
        Integer n = jdbc.queryForObject(sql, new MapSqlParameterSource("id", boardId), Integer.class);
        return n == null ? 0 : n;
    }

    private static Double sumOrNull(Double... values) {
        boolean any = false;
        double sum = 0;
        for (Double v : values) {
            if (v != null) {
                any = true;
                sum += v;
            }
        }
        return any ? sum : null;
    }

    private static Map<String, Double> mapOf(String k1, Double v1, String k2, Double v2, String k3, Double v3) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        return m;
    }

    private static List<String> concat(List<String> list, String extra) {
        List<String> out = new ArrayList<>(list);
        out.add(extra);
        return out;
    }
}
