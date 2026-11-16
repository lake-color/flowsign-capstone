package com.capstone.backend.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import com.capstone.backend.board.PopulationFilter.Axis;
import com.capstone.backend.common.ApiException;
import com.capstone.backend.common.Codes;

/**
 * 인구 조회 쿼리 해석과 값 검사 (API 명세 2장, 4.4). DB 불필요.
 *
 * 기간 합산 방식·연령 UNKNOWN 포함 규칙은 팀 미확정(명세 9장)이므로 여기서 검증하지 않는다.
 */
class PopulationFilterTest {

    @Test
    void emptyQueryUsesSpecDefaults() {
        PopulationFilter f = PopulationFilter.from(Map.of());

        assertThat(f.gender()).isEqualTo("ALL");
        assertThat(f.ageGroups()).isEqualTo(Codes.AGE_GROUPS);
        assertThat(f.flowType()).isEqualTo("ALL");
        assertThat(f.timeBands()).isEqualTo(Codes.TIME_BANDS);
        assertThat(f.periodKind()).isEqualTo("YEAR");
        assertThat(f.season()).isNull();
        assertThat(f.month()).isNull();
        assertThat(f.dayType()).isEqualTo("ALL");
    }

    @Test
    void listValuesAreTrimmedAndReturnedInCodeOrder() {
        PopulationFilter f = PopulationFilter.from(Map.of(
                "ageGroups", " AGE_30S , UNDER_20 ",
                "timeBands", "18_20,06_08"));

        assertThat(f.ageGroups()).containsExactly("UNDER_20", "AGE_30S");
        assertThat(f.timeBands()).containsExactly("06_08", "18_20");
    }

    @Test
    void csvIgnoresBlankItems() {
        assertThat(PopulationFilter.csv(null)).isEmpty();
        assertThat(PopulationFilter.csv("  ")).isEmpty();
        assertThat(PopulationFilter.csv("A, ,B,")).containsExactly("A", "B");
    }

    @Test
    void yearCoversTwelveMonthsOfDataYear() {
        List<String> months = PopulationFilter.from(Map.of()).months();

        assertThat(months).hasSize(12);
        assertThat(months.getFirst()).isEqualTo(Codes.DATA_YEAR + "01");
        assertThat(months.getLast()).isEqualTo(Codes.DATA_YEAR + "12");
    }

    @Test
    void winterIsJanuaryFebruaryDecember() {
        PopulationFilter f = PopulationFilter.from(Map.of("periodKind", "SEASON", "season", "WINTER"));

        assertThat(f.months()).containsExactly(
                Codes.DATA_YEAR + "01", Codes.DATA_YEAR + "02", Codes.DATA_YEAR + "12");
    }

    @Test
    void springIsMarchToMay() {
        PopulationFilter f = PopulationFilter.from(Map.of("periodKind", "SEASON", "season", "SPRING"));

        assertThat(f.months()).containsExactly(
                Codes.DATA_YEAR + "03", Codes.DATA_YEAR + "04", Codes.DATA_YEAR + "05");
    }

    @Test
    void monthIsSingleMonth() {
        PopulationFilter f = PopulationFilter.from(Map.of("periodKind", "MONTH", "month", "3"));

        assertThat(f.month()).isEqualTo(3);
        assertThat(f.months()).containsExactly(Codes.DATA_YEAR + "03");
    }

    static Stream<Map<String, String>> invalidQueries() {
        return Stream.of(
                Map.of("gender", "ABC"),
                Map.of("ageGroups", "AGE_99"),
                Map.of("flowType", "RUN"),
                Map.of("timeBands", "05_06"),
                Map.of("periodKind", "WEEK"),
                Map.of("periodKind", "SEASON"),
                Map.of("periodKind", "SEASON", "season", "RAINY"),
                Map.of("periodKind", "MONTH"),
                Map.of("periodKind", "MONTH", "month", "abc"),
                Map.of("periodKind", "MONTH", "month", "0"),
                Map.of("periodKind", "MONTH", "month", "13"),
                Map.of("dayType", "HOLIDAY"));
    }

    @ParameterizedTest
    @MethodSource("invalidQueries")
    void invalidValueIsValidationError(Map<String, String> query) {
        assertThatThrownBy(() -> PopulationFilter.from(query))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("VALIDATION_ERROR");
                });
    }

    @Test
    void whereAddsSelectedGenderAndMonths() {
        PopulationFilter f = PopulationFilter.from(Map.of("gender", "MALE", "periodKind", "MONTH", "month", "5"));
        MapSqlParameterSource params = new MapSqlParameterSource();

        String where = f.where(Axis.NONE, params);

        assertThat(where).startsWith(" AND ").contains("base_month IN (:months)").contains("gender = :gender");
        assertThat(params.getValue("gender")).isEqualTo("MALE");
        assertThat(params.getValue("months")).isEqualTo(List.of(Codes.DATA_YEAR + "05"));
    }

    @Test
    void whereAddsAgeAndTimeWhenSubsetSelected() {
        PopulationFilter f = PopulationFilter.from(Map.of("ageGroups", "AGE_20S", "timeBands", "08_10"));
        MapSqlParameterSource params = new MapSqlParameterSource();

        String where = f.where(Axis.NONE, params);

        assertThat(where).contains("age_group IN (:ages)").contains("time_slot IN (:times)");
        assertThat(params.getValue("ages")).isEqualTo(List.of("AGE_20S"));
        assertThat(params.getValue("times")).isEqualTo(List.of("08_10"));
    }

    @Test
    void whereAddsDayTypeWhenNotAll() {
        PopulationFilter f = PopulationFilter.from(Map.of("dayType", "WEEKEND"));
        MapSqlParameterSource params = new MapSqlParameterSource();

        assertThat(f.where(Axis.NONE, params)).contains("day_type = :dayType");
        assertThat(params.getValue("dayType")).isEqualTo("WEEKEND");
    }

    @Test
    void whereSkipsChartAxis() {
        PopulationFilter f = PopulationFilter.from(Map.of(
                "gender", "FEMALE", "ageGroups", "AGE_20S", "timeBands", "08_10"));

        assertThat(f.where(Axis.GENDER, new MapSqlParameterSource())).doesNotContain("gender");
        assertThat(f.where(Axis.AGE, new MapSqlParameterSource())).doesNotContain("age_group");
        assertThat(f.where(Axis.TIME, new MapSqlParameterSource())).doesNotContain("time_slot");
        assertThat(f.where(Axis.PERIOD, new MapSqlParameterSource())).doesNotContain("base_month");
    }

    @Test
    void flowExpressionPicksSelectedColumn() {
        assertThat(PopulationFilter.from(Map.of("flowType", "STAY")).flowExpression()).isEqualTo("SUM(stay)");
        assertThat(PopulationFilter.from(Map.of("flowType", "WALK")).flowExpression()).isEqualTo("SUM(walk)");
        assertThat(PopulationFilter.from(Map.of("flowType", "DRIVE")).flowExpression()).isEqualTo("SUM(drive)");
        assertThat(PopulationFilter.from(Map.of()).flowExpression())
                .contains("SUM(stay)").contains("SUM(walk)").contains("SUM(drive)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliedEchoesPeriod() {
        Map<String, Object> month = PopulationFilter.from(Map.of("periodKind", "MONTH", "month", "7")).applied();
        Map<String, Object> monthPeriod = (Map<String, Object>) month.get("period");
        assertThat(monthPeriod).containsEntry("kind", "MONTH").containsEntry("year", Codes.DATA_YEAR)
                .containsEntry("month", 7).doesNotContainKey("season");

        Map<String, Object> year = PopulationFilter.from(Map.of()).applied();
        Map<String, Object> yearPeriod = (Map<String, Object>) year.get("period");
        assertThat(yearPeriod).containsEntry("kind", "YEAR").doesNotContainKeys("season", "month");
        assertThat(year).containsEntry("gender", "ALL").containsEntry("dayType", "ALL");
    }
}
