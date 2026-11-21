package com.capstone.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.capstone.backend.board.PopulationFilter;
import com.capstone.backend.common.ApiException;
import com.capstone.backend.recommendation.RecommendationScorer.CellRow;

/**
 * 추천 점수 입력 만들기: Cell 행 → 게시대별 가중 일평균, 조건 SQL의 UNKNOWN 규칙, 지역·용도 검사.
 * DB 대신 mock JdbcTemplate과 직접 만든 Cell 행을 쓴다.
 */
class RecommendationScorerTest {

    private static final double EPS = 1e-9;

    private static CellRow row(long board, boolean center, double distance, Double n, Double t, Double tf) {
        return new CellRow(board, 4, 10000, center, distance, n, t, tf);
    }

    @Test
    void cellsAreWeightedAndDividedByDays() {
        List<ScoreFormula.Input> inputs = RecommendationScorer.inputs(List.of(
                row(1, true, 30, 1000.0, 200.0, 100.0),
                row(1, false, 250, 2000.0, 400.0, 200.0),
                row(1, false, 250, 4000.0, 800.0, 400.0)), 10);

        ScoreFormula.Input in = inputs.getFirst();
        // 0.8 × 1000 + 0.1 × 2000 + 0.1 × 4000 = 1400 → 10일 → 140
        assertThat(in.n()).isCloseTo(140, within(EPS));
        assertThat(in.t()).isCloseTo(28, within(EPS));
        assertThat(in.tf()).isCloseTo(14, within(EPS));
        assertThat(in.expectedCellCount()).isEqualTo(3);
        assertThat(in.matchedCellCount()).isEqualTo(3);
    }

    @Test
    void cellsWithoutDataAreSkippedAndBoardWithoutAnyIsDropped() {
        List<ScoreFormula.Input> inputs = RecommendationScorer.inputs(List.of(
                row(1, true, 30, 1000.0, 200.0, 100.0),
                row(1, false, 250, null, null, null),
                row(2, true, 10, null, null, null)), 1);

        assertThat(inputs).hasSize(1);
        assertThat(inputs.getFirst().n()).isCloseTo(1000, within(EPS));
        assertThat(inputs.getFirst().matchedCellCount()).isEqualTo(1);
        assertThat(inputs.getFirst().expectedCellCount()).isEqualTo(2);
    }

    @Test
    void sqlAppliesTargetOnlyToTAndTf() {
        PopulationFilter f = PopulationFilter.from(Map.of("gender", "FEMALE", "ageGroups", "AGE_20S,AGE_30S",
                "flowType", "WALK", "timeBands", "16_18,18_20", "periodKind", "SEASON", "season", "SUMMER",
                "dayType", "WEEKEND"));
        MapSqlParameterSource p = new MapSqlParameterSource();

        String sql = RecommendationScorer.sql(f, p);

        assertThat(sql).contains("SUM(walk) FILTER (WHERE gender = :gender AND age_group IN (:ages)) AS tf")
                .contains("time_slot IN (:times)").contains("day_type = :dayType")
                .contains("FILTER (WHERE TRUE)");
        assertThat(p.getValue("months")).isEqualTo(List.of("202506", "202507", "202508"));
        assertThat(p.getValue("gender")).isEqualTo("FEMALE");
    }

    @Test
    void sqlKeepsUnknownWhenWholeAxisIsSelected() {
        // 기본 조건: 성별 ALL, 연령·시간대 전체, 요일 ALL → 해당 조건을 걸지 않아 UNKNOWN 행도 포함
        PopulationFilter f = PopulationFilter.from(Map.of());
        MapSqlParameterSource p = new MapSqlParameterSource();

        String sql = RecommendationScorer.sql(f, p);

        assertThat(sql).doesNotContain("gender =").doesNotContain("age_group IN")
                .doesNotContain("time_slot IN").doesNotContain("day_type =");
        assertThat(p.hasValue("gender")).isFalse();
    }

    @Test
    void emptyOrUnknownCityIsValidationError() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        RecommendationScorer scorer = new RecommendationScorer(jdbc);
        PopulationFilter f = PopulationFilter.from(Map.of());

        assertThatThrownBy(() -> scorer.score(List.of(), List.of("COMMERCIAL"), f))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
        assertThatThrownBy(() -> scorer.score(List.of("SEOUL"), List.of("COMMERCIAL"), f))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
        assertThatThrownBy(() -> scorer.score(List.of("ANYANG"), List.of("PARKING"), f))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(jdbc);
    }
}
