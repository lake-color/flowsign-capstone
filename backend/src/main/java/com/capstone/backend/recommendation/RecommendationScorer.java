package com.capstone.backend.recommendation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.capstone.backend.board.DayCount;
import com.capstone.backend.board.PopulationFilter;
import com.capstone.backend.common.ApiException;
import com.capstone.backend.common.Codes;

/**
 * 추천 후보 게시대의 점수를 계산해 정렬한다 (명세 4.6 계산 부분). 응답 형태·페이지·추천 이유 문구는 추천 API가 만든다.
 *
 * 1. 후보(지역·용도)의 3×3 Cell마다 조건에 맞는 N·T·Tf 합계를 한 번에 조회한다.
 * 2. 게시대별로 데이터가 있는 Cell만 {@link CellWeights}로 가중 합 → 선택 기간·요일의 일평균(명/일, {@link DayCount}).
 * 3. {@link ScoreFormula}로 점수·정렬.
 *
 * 주변 칸을 차등 가중하려면 Cell별 값이 필요해, 3×3이 이미 합쳐진 board_population 대신 population_fact를
 * 후보 게시대의 Cell(board_cell)로 좁혀 읽는다. 인덱스 (cell_id, base_month)를 쓴다.
 *
 * 조건 적용은 조회 API({@link PopulationFilter})와 같은 UNKNOWN 규칙을 따른다.
 *  - 성별 ALL이면 UNKNOWN 포함, 연령·시간대는 전체 선택일 때만 UNKNOWN 포함, 요일 ALL이면 UNKNOWN 포함
 *  - N(전체 유동)은 성별·연령을 걸지 않는다. T·Tf는 N과 같은 행에서 성별·연령을 건다.
 * 요일 구분 지원 여부(UNSUPPORTED_FILTER)와 데이터 미적재(DATA_NOT_READY) 검사는 추천 API가 먼저 한다.
 *
 * 배점·리프트 범위·최소 타깃 인구·비율 보정·Cell 가중치는 application.yml의 flowsign.recommendation.*로 바꾼다 (PRD N15).
 */
@Service
public class RecommendationScorer {

    private static final String ALL_FLOW = "COALESCE(stay, 0) + COALESCE(walk, 0) + COALESCE(drive, 0)";

    private final NamedParameterJdbcTemplate jdbc;
    private final ScoreFormula.Params params;
    private final double centerWeight;
    private final double neighborSigmaMeters;

    @Autowired
    public RecommendationScorer(NamedParameterJdbcTemplate jdbc,
                                @Value("${flowsign.recommendation.size-points:40}") double sizePoints,
                                @Value("${flowsign.recommendation.share-points:40}") double sharePoints,
                                @Value("${flowsign.recommendation.flow-points:20}") double flowPoints,
                                @Value("${flowsign.recommendation.lift-span:2.0}") double liftSpan,
                                @Value("${flowsign.recommendation.min-target-per-day:0}") double minTargetPerDay,
                                @Value("${flowsign.recommendation.ratio-shrink:0.5}") double ratioShrink,
                                @Value("${flowsign.recommendation.size-min-span:10}") double sizeMinSpan,
                                @Value("${flowsign.recommendation.center-weight:0.8}") double centerWeight,
                                @Value("${flowsign.recommendation.neighbor-sigma-meters:250}") double neighborSigmaMeters) {
        this(jdbc, new ScoreFormula.Params(sizePoints, sharePoints, flowPoints, liftSpan, minTargetPerDay,
                        ratioShrink, sizeMinSpan),
                centerWeight, neighborSigmaMeters);
    }

    /** 기본 파라미터 (테스트용) */
    RecommendationScorer(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, ScoreFormula.Params.DEFAULT, CellWeights.CENTER_WEIGHT, CellWeights.NEIGHBOR_SIGMA_METERS);
    }

    RecommendationScorer(NamedParameterJdbcTemplate jdbc, ScoreFormula.Params params, double centerWeight,
                         double neighborSigmaMeters) {
        this.jdbc = jdbc;
        this.params = params;
        this.centerWeight = centerWeight;
        this.neighborSigmaMeters = neighborSigmaMeters;
        CellWeights.of(List.of(), centerWeight, neighborSigmaMeters); // 잘못된 설정이면 시작할 때 바로 알린다
    }

    /** 현재 적용 중인 산식 파라미터 (응답·안내 문구에 쓸 수 있다) */
    public ScoreFormula.Params params() {
        return params;
    }

    /**
     * @param cities   API 지역 코드 (ANYANG 등), 1개 이상
     * @param purposes 게시대 용도 (COMMERCIAL 등), 1개 이상
     * @return 점수 내림차순(명세 4.6 정렬). 조건에 맞는 인구 데이터가 하나도 없는 게시대는 빠진다.
     */
    public List<ScoreFormula.Result> score(List<String> cities, List<String> purposes, PopulationFilter f) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("cities", dbCities(cities))
                .addValue("purposes", checked(purposes, Codes.PURPOSES, "purposes"));
        List<CellRow> rows = jdbc.query(sql(f, p), p, (rs, i) -> new CellRow(
                rs.getLong("board_id"), rs.getObject("face_count", Integer.class),
                rs.getObject("posting_fee", Integer.class), rs.getBoolean("is_center"),
                rs.getDouble("distance_meters"), rs.getObject("n", Double.class),
                rs.getObject("t", Double.class), rs.getObject("tf", Double.class)));
        return ScoreFormula.scoreAndRank(inputs(rows, f.days(), centerWeight, neighborSigmaMeters), f.flowType(),
                params);
    }

    /** board_cell 한 행 + 그 Cell의 조건별 합계. n·t·tf가 null이면 조건에 맞는 데이터가 없는 칸. */
    record CellRow(long boardId, Integer faceCount, Integer postingFee, boolean center, double distanceMeters,
                   Double n, Double t, Double tf) {

        boolean hasData() {
            return n != null && t != null && tf != null;
        }
    }

    /** Cell 행 → 게시대별 가중 일평균. 데이터가 있는 칸이 없으면 그 게시대는 넣지 않는다. */
    static List<ScoreFormula.Input> inputs(List<CellRow> rows, int days) {
        return inputs(rows, days, CellWeights.CENTER_WEIGHT, CellWeights.NEIGHBOR_SIGMA_METERS);
    }

    static List<ScoreFormula.Input> inputs(List<CellRow> rows, int days, double centerWeight,
                                           double neighborSigmaMeters) {
        Map<Long, List<CellRow>> byBoard = new LinkedHashMap<>();
        for (CellRow row : rows) {
            byBoard.computeIfAbsent(row.boardId(), k -> new ArrayList<>()).add(row);
        }
        List<ScoreFormula.Input> inputs = new ArrayList<>();
        for (List<CellRow> cells : byBoard.values()) {
            List<CellRow> present = cells.stream().filter(CellRow::hasData).toList();
            if (present.isEmpty()) {
                continue;
            }
            double[] w = CellWeights.of(present.stream()
                    .map(c -> new CellWeights.Cell(c.center(), c.distanceMeters())).toList(),
                    centerWeight, neighborSigmaMeters);
            double n = 0;
            double t = 0;
            double tf = 0;
            for (int i = 0; i < present.size(); i++) {
                n += w[i] * present.get(i).n();
                t += w[i] * present.get(i).t();
                tf += w[i] * present.get(i).tf();
            }
            CellRow first = cells.getFirst();
            inputs.add(new ScoreFormula.Input(first.boardId(), first.faceCount(), first.postingFee(),
                    n / days, t / days, tf / days, cells.size(), present.size()));
        }
        return inputs;
    }

    static String sql(PopulationFilter f, MapSqlParameterSource p) {
        List<String> rowFilter = new ArrayList<>(List.of("base_month IN (:months)"));
        p.addValue("months", f.months());
        if (f.timeBands().size() < Codes.TIME_BANDS.size()) {
            rowFilter.add("time_slot IN (:times)");
            p.addValue("times", f.timeBands());
        }
        if (!f.dayType().equals("ALL")) {
            rowFilter.add("day_type = :dayType");
            p.addValue("dayType", f.dayType());
        }

        List<String> target = new ArrayList<>();
        if (!f.gender().equals("ALL")) {
            target.add("gender = :gender");
            p.addValue("gender", f.gender());
        }
        if (f.ageGroups().size() < Codes.AGE_GROUPS.size()) {
            target.add("age_group IN (:ages)");
            p.addValue("ages", f.ageGroups());
        }
        String targetCond = target.isEmpty() ? "TRUE" : String.join(" AND ", target);
        String flowCol = switch (f.flowType()) {
            case "STAY" -> "stay";
            case "WALK" -> "walk";
            case "DRIVE" -> "drive";
            default -> null;
        };

        // 체류·보행·주행이 모두 NULL인 행만 있으면 0이 아니라 NULL(값 없음)
        String n = nullableFlowSum("TRUE");
        String t = nullableFlowSum(targetCond);
        String tf = flowCol == null ? t : "SUM(" + flowCol + ") FILTER (WHERE " + targetCond + ")";

        return """
                WITH candidate AS (
                    SELECT bc.board_id, bc.cell_id, bc.is_center, bc.distance_meters, b.face_count, b.posting_fee
                    FROM board_cell bc
                    JOIN board b ON b.board_id = bc.board_id
                    WHERE b.city IN (:cities) AND b.purpose IN (:purposes)
                ), cell_sum AS (
                    SELECT cell_id, %s AS n, %s AS t, %s AS tf
                    FROM population_fact
                    WHERE cell_id IN (SELECT DISTINCT cell_id FROM candidate) AND %s
                    GROUP BY cell_id
                )
                SELECT c.board_id, c.face_count, c.posting_fee, c.is_center, c.distance_meters, s.n, s.t, s.tf
                FROM candidate c
                LEFT JOIN cell_sum s ON s.cell_id = c.cell_id
                ORDER BY c.board_id, c.is_center DESC, c.distance_meters
                """.formatted(n, t, tf, String.join(" AND ", rowFilter));
    }

    private static String nullableFlowSum(String cond) {
        String filter = " FILTER (WHERE " + cond + ")";
        return "(CASE WHEN COUNT(stay)" + filter + " + COUNT(walk)" + filter + " + COUNT(drive)" + filter
                + " = 0 THEN NULL ELSE SUM(" + ALL_FLOW + ")" + filter + " END)";
    }

    private static List<String> dbCities(List<String> cities) {
        return checked(cities, Codes.CITIES, "cities").stream().map(Codes.CITY_TO_DB::get).toList();
    }

    private static List<String> checked(List<String> values, List<String> allowed, String field) {
        if (values == null || values.isEmpty()) {
            throw ApiException.badRequest(field + "를 하나 이상 선택해 주세요.");
        }
        for (String v : values) {
            if (!allowed.contains(v)) {
                throw ApiException.badRequest(field + " 값이 올바르지 않습니다: " + v);
            }
        }
        return values;
    }
}
