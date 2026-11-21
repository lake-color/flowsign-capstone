package com.capstone.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import com.capstone.backend.board.PopulationFilter;
import com.capstone.backend.common.ApiException;

/**
 * 추천 이유 문구와 DB 조회 전 요청 검사를 DB 없이 확인한다.
 */
class RecommendationServiceTest {

    private static final ScoreFormula.Input INPUT = new ScoreFormula.Input(1, 4, null, 100, 30, 20, 9, 9);

    private static ScoreFormula.Result result(double sizeNormalized, Double shareLift, Double flowLift) {
        List<ScoreFormula.Component> parts = new ArrayList<>();
        parts.add(new ScoreFormula.Component("A", "유동 규모", 100, null, null, sizeNormalized, 40, 40 * sizeNormalized));
        parts.add(new ScoreFormula.Component("B", "타깃 비율", 0.3, 0.25, shareLift, 0.6, 40, 24));
        if (flowLift != null) {
            parts.add(new ScoreFormula.Component("C", "유형 비중", 0.6, 0.5, flowLift, 0.6, 20, 12));
        }
        return new ScoreFormula.Result(INPUT, 0, parts);
    }

    private static PopulationFilter filter(Map<String, String> q) {
        return PopulationFilter.from(q);
    }

    @Test
    void reasonNamesTargetAndFlowLiftsThenLargeFlow() {
        PopulationFilter women2030Walking = filter(Map.of("gender", "FEMALE", "ageGroups", "AGE_20S,AGE_30S",
                "flowType", "WALK"));

        String reason = RecommendationService.reason(result(0.9, 1.34, 1.2), women2030Walking);

        assertThat(reason).isEqualTo("20대·30대 여성 비율이 후보 평균의 1.3배입니다. "
                + "보행 비중이 후보 평균의 1.2배입니다. 후보 중 유동 규모가 큰 편입니다.");
    }

    @Test
    void reasonLeavesOutBelowAverageRatios() {
        PopulationFilter men = filter(Map.of("gender", "MALE", "flowType", "STAY"));

        // 타깃 비율이 평균보다 낮고(0.8배) 유형 비중도 기준 미만(1.05배) → 강점이 아니므로 넣지 않는다
        String reason = RecommendationService.reason(result(0.9, 0.8, 1.05), men);

        assertThat(reason).isEqualTo("후보 중 유동 규모가 큰 편입니다.");
    }

    @Test
    void reasonWithoutTargetConditionSkipsShare() {
        // 성별·연령 전체면 타깃 비율 비교가 의미 없다 (lift가 커도 쓰지 않음)
        String reason = RecommendationService.reason(result(0.2, 1.5, null), filter(Map.of()));

        assertThat(reason).isEqualTo("유동 규모와 타깃 비율이 후보 평균과 비슷한 수준입니다.");
    }

    @Test
    void rejectsMissingCriteriaAndBadPagingBeforeQuerying() {
        // 검사에서 걸리므로 DB·점수 계산 객체는 쓰이지 않는다
        RecommendationService service = new RecommendationService(null, null, null, null, null);
        RecommendationRequest.Criteria criteria = new RecommendationRequest.Criteria(List.of("ANYANG"), null,
                List.of("AGE_20S"), null, List.of("08_10"), null, null, List.of("COMMERCIAL"));

        assertValidationError(() -> service.search(null));
        assertValidationError(() -> service.search(new RecommendationRequest(null, 0, 10)));
        assertValidationError(() -> service.search(new RecommendationRequest(criteria, -1, 10)));
        assertValidationError(() -> service.search(new RecommendationRequest(criteria, 0, 0)));
        assertValidationError(() -> service.search(new RecommendationRequest(criteria, 0, 51)));
        // 빈 연령 배열은 조회 API와 같은 검사로 400
        RecommendationRequest.Criteria noAges = new RecommendationRequest.Criteria(List.of("ANYANG"), null,
                List.of(), null, List.of("08_10"), null, null, List.of("COMMERCIAL"));
        assertValidationError(() -> service.search(new RecommendationRequest(noAges, 0, 10)));
    }

    @Test
    void rejectsYearOutsideData() {
        RecommendationService service = new RecommendationService(null, null, null, null, null);
        RecommendationRequest.Criteria year2024 = new RecommendationRequest.Criteria(List.of("ANYANG"), null,
                List.of("AGE_20S"), null, List.of("08_10"), new RecommendationRequest.Period("YEAR", 2024, null, null),
                null, List.of("COMMERCIAL"));

        assertThatThrownBy(() -> service.search(new RecommendationRequest(year2024, 0, 10)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("UNSUPPORTED_FILTER"));
    }

    private static void assertValidationError(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
    }
}
