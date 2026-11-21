package com.capstone.backend.recommendation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.capstone.backend.board.BoardRepository;
import com.capstone.backend.board.DayCount;
import com.capstone.backend.board.PopulationFilter;
import com.capstone.backend.board.PopulationFilter.Axis;
import com.capstone.backend.common.ApiException;
import com.capstone.backend.common.Codes;
import com.capstone.backend.common.DatasetInfoReader;
import com.capstone.backend.common.DayTypeReader;
import com.capstone.backend.common.PopulationValue;

/**
 * 추천 API (명세 4.6). 점수 계산·정렬은 {@link RecommendationScorer}가 하고, 여기서는 검사·페이지·응답 조립을 한다.
 *
 * - 페이지: 처음 10개, 다음 page로 10개씩 더 (명세 4.6). 결과가 없으면 200 + items []
 * - 목록의 targetPopulation은 점수용 가중 합이 아니라 상세 화면(4.4)과 같은 3×3 합계(board_population)의 일평균이다.
 *   점수에 쓴 값과 숫자가 다를 수 있다.
 * - 추천 이유는 점수 구성(scoreBreakdown)의 lift(후보 평균의 몇 배)와 유동 규모 점수로 만든다.
 *
 * 오류 기준은 인구 조회 API와 같다 (명세 5장): 조건 오류 400 VALIDATION_ERROR, 데이터에 없는 요일·연도
 * 400 UNSUPPORTED_FILTER, 인구 집계·3×3 매핑 미적재 503 DATA_NOT_READY.
 */
@Service
public class RecommendationService {

    static final int DEFAULT_PAGE_SIZE = 10;
    /** /meta maxRecommendationPageSize와 같은 값 */
    static final int MAX_PAGE_SIZE = 50;

    static final String NOTICE = "유동인구 기반 비교 지표이며 실제 광고 노출 인원이 아닙니다.";

    /** 비율이 후보 평균의 이 배수 이상이면 추천 이유에 넣는다. 임시 기준값(팀 미확정) */
    static final double REASON_MIN_LIFT = 1.1;
    /** 유동 규모 점수(0~1)가 이 값 이상이면 추천 이유에 넣는다. 임시 기준값(팀 미확정) */
    static final double REASON_MIN_SIZE = 0.7;

    private static final Map<String, String> AGE_TEXT = Map.of(
            "UNDER_20", "20세 미만", "AGE_20S", "20대", "AGE_30S", "30대",
            "AGE_40S", "40대", "AGE_50S", "50대", "AGE_60_PLUS", "60대 이상");
    private static final Map<String, String> GENDER_TEXT = Map.of("MALE", "남성", "FEMALE", "여성");
    private static final Map<String, String> FLOW_TEXT = Map.of("STAY", "체류", "WALK", "보행", "DRIVE", "주행");

    private final RecommendationScorer scorer;
    private final BoardRepository boards;
    private final NamedParameterJdbcTemplate jdbc;
    private final DatasetInfoReader dataset;
    private final DayTypeReader dayTypes;

    public RecommendationService(RecommendationScorer scorer, BoardRepository boards, NamedParameterJdbcTemplate jdbc,
                                 DatasetInfoReader dataset, DayTypeReader dayTypes) {
        this.scorer = scorer;
        this.boards = boards;
        this.jdbc = jdbc;
        this.dataset = dataset;
        this.dayTypes = dayTypes;
    }

    public Map<String, Object> search(RecommendationRequest request) {
        if (request == null || request.criteria() == null) {
            throw ApiException.badRequest("추천 조건(criteria)이 없습니다.");
        }
        RecommendationRequest.Criteria c = request.criteria();
        int page = request.page() == null ? 0 : request.page();
        int pageSize = request.pageSize() == null ? DEFAULT_PAGE_SIZE : request.pageSize();
        if (page < 0) {
            throw ApiException.badRequest("page는 0 이상이어야 합니다.");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("pageSize는 1~" + MAX_PAGE_SIZE + " 사이여야 합니다.");
        }
        PopulationFilter filter = RecommendationScorer.validated(toFilter(c));
        ensureReady();
        ensureDayTypeSupported(filter);

        List<ScoreFormula.Result> ranked = scorer.score(c.cities(), c.purposes(), filter);
        int from = (int) Math.min((long) page * pageSize, ranked.size());
        int to = Math.min(from + pageSize, ranked.size());
        List<ScoreFormula.Result> pageResults = ranked.subList(from, to);

        Map<Long, BoardRepository.Board> boardById = new HashMap<>();
        for (BoardRepository.Board b : boards.findAll(c.cities(), c.purposes(), null, null, null, null)) {
            boardById.put(Long.parseLong(b.boardId()), b);
        }
        Map<Long, Double> target = targetPopulation(
                pageResults.stream().map(r -> r.input().boardId()).toList(), filter);

        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < pageResults.size(); i++) {
            ScoreFormula.Result r = pageResults.get(i);
            items.add(item(from + i + 1, r, boardById.get(r.input().boardId()),
                    target.get(r.input().boardId()), filter));
        }

        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("cities", c.cities());
        criteria.putAll(filter.applied());
        criteria.put("purposes", c.purposes());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("criteria", criteria);
        data.put("items", items);
        data.put("page", page);
        data.put("pageSize", pageSize);
        data.put("totalItems", ranked.size());
        data.put("hasNext", to < ranked.size());
        data.put("algorithmVersion", ScoreFormula.VERSION);
        data.put("algorithmStatus", "PROVISIONAL");
        data.put("notice", NOTICE);
        data.put("dataNotice", dataset.current().populationNotice());
        return data;
    }

    /** 요청 본문 → PopulationFilter. 빠진 값은 조회 API와 같은 기본값을 쓰도록 null로 넘긴다. */
    private static PopulationFilter toFilter(RecommendationRequest.Criteria c) {
        RecommendationRequest.Period period = c.period();
        if (period != null && period.year() != null && period.year() != Codes.DATA_YEAR) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILTER",
                    "데이터에 없는 연도입니다: " + period.year());
        }
        return new PopulationFilter(c.gender(), c.ageGroups(), c.flowType(), c.timeBands(),
                period == null ? null : period.kind(), period == null ? null : period.season(),
                period == null ? null : period.month(), c.dayType());
    }

    private Map<String, Object> item(int rank, ScoreFormula.Result r, BoardRepository.Board board, Double targetSum,
                                     PopulationFilter f) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("rank", rank);
        item.put("boardId", String.valueOf(r.input().boardId()));
        item.put("name", board == null ? null : board.name());
        item.put("city", board == null ? null : board.city());
        item.put("dong", board == null ? null : board.dong());
        item.put("latitude", board == null ? null : board.latitude());
        item.put("longitude", board == null ? null : board.longitude());
        item.put("purpose", board == null ? null : board.purpose());
        item.put("canInquire", board != null && board.canInquire());
        item.put("score", round(r.score(), 1));
        item.put("displayScore", Math.round(r.score()));
        item.put("targetPopulation", PopulationValue.of(DayCount.perDay(targetSum, f.days())));
        item.put("reason", reason(r, f));
        item.put("scoreBreakdown", r.components().stream().map(RecommendationService::breakdown).toList());
        return item;
    }

    /** 프론트가 이미 contribution으로 읽으므로 점수(points)를 그 이름으로 내보낸다. */
    private static Map<String, Object> breakdown(ScoreFormula.Component part) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", part.key());
        m.put("label", part.label());
        m.put("rawValue", round(part.rawValue(), 3));
        m.put("baseline", part.baseline() == null ? null : round(part.baseline(), 3));
        m.put("lift", part.lift() == null ? null : round(part.lift(), 3));
        m.put("normalized", round(part.normalized(), 3));
        m.put("weight", round(part.weight(), 1));
        m.put("contribution", round(part.points(), 1));
        return m;
    }

    /**
     * 추천 이유 문구. 후보 평균보다 두드러진 항목만 쓴다(평균보다 낮은 비율은 강점이 아니므로 넣지 않는다).
     * 예: "20대·30대 여성 비율이 후보 평균의 1.3배입니다. 보행 비중이 후보 평균의 1.2배입니다."
     */
    static String reason(ScoreFormula.Result r, PopulationFilter f) {
        List<String> sentences = new ArrayList<>();
        String target = targetText(f);
        boolean largeFlow = false;
        for (ScoreFormula.Component part : r.components()) {
            switch (part.key()) {
                case "A" -> largeFlow = part.normalized() >= REASON_MIN_SIZE;
                case "B" -> {
                    // 성별·연령 전체면 모든 게시대의 타깃 비율이 1이라 비교할 것이 없다.
                    if (target != null && part.lift() != null && part.lift() >= REASON_MIN_LIFT) {
                        sentences.add(target + " 비율이 후보 평균의 " + times(part.lift()) + "입니다.");
                    }
                }
                case "C" -> {
                    if (part.lift() != null && part.lift() >= REASON_MIN_LIFT) {
                        sentences.add(FLOW_TEXT.get(f.flowType()) + " 비중이 후보 평균의 " + times(part.lift()) + "입니다.");
                    }
                }
                default -> {
                }
            }
        }
        // 타깃·유형 비율을 먼저 쓰고 유동 규모는 뒤에 붙인다.
        if (largeFlow) {
            sentences.add("후보 중 유동 규모가 큰 편입니다.");
        }
        if (sentences.isEmpty()) {
            return "유동 규모와 타깃 비율이 후보 평균과 비슷한 수준입니다.";
        }
        return String.join(" ", sentences);
    }

    /** 성별·연령 조건 → "20대·30대 여성". 둘 다 전체면 null */
    private static String targetText(PopulationFilter f) {
        List<String> parts = new ArrayList<>();
        if (f.ageGroups().size() < Codes.AGE_GROUPS.size()) {
            parts.add(String.join("·", f.ageGroups().stream().map(AGE_TEXT::get).toList()));
        }
        if (GENDER_TEXT.containsKey(f.gender())) {
            parts.add(GENDER_TEXT.get(f.gender()));
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    private static String times(double lift) {
        return String.format(Locale.ROOT, "%.1f배", lift);
    }

    /** 게시대별 3×3 합계(선택 유동 유형, 타깃 조건 적용). 상세 화면(4.4)과 같은 board_population 기준 */
    private Map<Long, Double> targetPopulation(List<Long> boardIds, PopulationFilter f) {
        Map<Long, Double> result = new HashMap<>();
        if (boardIds.isEmpty()) {
            return result;
        }
        MapSqlParameterSource p = new MapSqlParameterSource("ids", boardIds);
        String sql = "SELECT board_id, " + f.flowExpression() + " AS v FROM board_population"
                + " WHERE board_id IN (:ids)" + f.where(Axis.NONE, p) + " GROUP BY board_id";
        jdbc.query(sql, p, rs -> {
            result.put(rs.getLong("board_id"), rs.getObject("v", Double.class));
        });
        return result;
    }

    /** 점수는 population_fact와 board_cell을 읽고, 목록 인구는 board_population을 읽는다. */
    private void ensureReady() {
        for (String table : List.of("population_fact", "board_population")) {
            if (!exists(table)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DATA_NOT_READY",
                        "인구 집계 데이터가 아직 적재되지 않았습니다.");
            }
        }
        if (!exists("board_cell")) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DATA_NOT_READY",
                    "게시대 3×3 영향권 매핑이 아직 산출되지 않았습니다.");
        }
    }

    /** table: 코드 안의 고정값만 넘긴다. */
    private boolean exists(String table) {
        return Boolean.TRUE.equals(jdbc.getJdbcTemplate()
                .queryForObject("SELECT EXISTS (SELECT 1 FROM " + table + ")", Boolean.class));
    }

    private void ensureDayTypeSupported(PopulationFilter f) {
        if (!f.dayType().equals("ALL") && !dayTypes.available().contains(f.dayType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILTER",
                    "데이터에 없는 요일 구분입니다: " + f.dayType());
        }
    }

    private static double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }
}
