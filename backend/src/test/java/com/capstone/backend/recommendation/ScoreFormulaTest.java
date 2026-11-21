package com.capstone.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.capstone.backend.recommendation.ScoreFormula.Input;
import com.capstone.backend.recommendation.ScoreFormula.Result;

/**
 * 추천 점수 산식 (A 유동 규모 40 / B 타깃 비율 40 / C 유형 비중 20)과 명세 4.6 정렬.
 */
class ScoreFormulaTest {

    private static final double EPS = 1e-9;

    private static Input input(long id, double n, double t, double tf) {
        return new Input(id, 5, 10000, n, t, tf, 9, 9);
    }

    private static List<Long> ids(List<Result> results) {
        return results.stream().map(r -> r.input().boardId()).toList();
    }

    @Test
    void liftScoreIsHalfAtBaselineAndFullAtDouble() {
        assertThat(ScoreFormula.liftScore(0.2, 0.2, 2)).isCloseTo(0.5, within(EPS));
        assertThat(ScoreFormula.liftScore(0.4, 0.2, 2)).isCloseTo(1.0, within(EPS));
        assertThat(ScoreFormula.liftScore(0.1, 0.2, 2)).isCloseTo(0.0, within(EPS));
        assertThat(ScoreFormula.liftScore(0.9, 0.2, 2)).isCloseTo(1.0, within(EPS));
        assertThat(ScoreFormula.liftScore(0.0, 0.2, 2)).isCloseTo(0.0, within(EPS));
        assertThat(ScoreFormula.liftScore(0.3, 0.0, 2)).isCloseTo(0.5, within(EPS));
        // span 1.5: 평균의 1.5배면 만점
        assertThat(ScoreFormula.liftScore(0.3, 0.2, 1.5)).isCloseTo(1.0, within(EPS));
    }

    @Test
    void sizeUsesLogScaleBetweenCandidates() {
        // 100, 1000, 10000 → log 척도에서 0, 0.5, 1
        List<Result> r = ScoreFormula.scoreAndRank(List.of(
                input(1, 100, 20, 5), input(2, 1000, 200, 50), input(3, 10000, 2000, 500)), "WALK");

        assertThat(ids(r)).containsExactly(3L, 2L, 1L);
        assertThat(r.get(1).components().getFirst().normalized()).isCloseTo(0.5, within(EPS));
        assertThat(r.get(0).components().getFirst().points()).isCloseTo(40, within(EPS));
        // 비율이 모두 같으므로 B·C는 기준과 같아 각각 배점의 절반
        assertThat(r.get(0).score()).isCloseTo(40 + 20 + 10, within(EPS));
    }

    private static final ScoreFormula.Params NO_SHRINK = new ScoreFormula.Params(40, 40, 20, 2.0, 0, 0, 10);

    @Test
    void nearlyEqualSizesAreNotStretchedToFullRange() {
        // 20 vs 18.8: 최소 10배 범위로 잡으므로 작은 쪽도 거의 만점 (최소~최대만 쓰면 0점이 된다)
        List<Result> r = ScoreFormula.scoreAndRank(List.of(input(1, 18.8, 8.4, 8.4), input(2, 20, 2, 1)), "WALK");

        Result smaller = r.stream().filter(x -> x.input().boardId() == 1L).findFirst().orElseThrow();
        assertThat(smaller.components().getFirst().normalized())
                .isCloseTo(1 - Math.log(20 / 18.8) / Math.log(10), within(EPS));
        // 타깃이 4배 많은 1번이 1위
        assertThat(ids(r)).containsExactly(1L, 2L);
    }

    @Test
    void higherTargetShareWinsWhenSizeIsEqual() {
        List<Result> r = ScoreFormula.scoreAndRank(List.of(
                input(1, 1000, 100, 30), input(2, 1000, 300, 90)), "WALK", NO_SHRINK);

        assertThat(ids(r)).containsExactly(2L, 1L);
        ScoreFormula.Component share = r.getFirst().components().get(1);
        assertThat(share.key()).isEqualTo("B");
        assertThat(share.baseline()).isCloseTo(0.2, within(EPS));
        assertThat(share.lift()).isCloseTo(1.5, within(EPS));
    }

    @Test
    void flowTypeAllDropsFlowComponentAndRescalesToFiftyFifty() {
        List<Result> r = ScoreFormula.scoreAndRank(List.of(input(1, 1000, 200, 200)), "ALL");

        assertThat(r.getFirst().components()).hasSize(2);
        assertThat(r.getFirst().components().get(0).weight()).isCloseTo(50, within(EPS));
        assertThat(r.getFirst().components().get(1).weight()).isCloseTo(50, within(EPS));
        // 후보 1개: 규모 만점 50 + 비율이 기준과 같아 25
        assertThat(r.getFirst().score()).isCloseTo(75, within(EPS));
    }

    @Test
    void boardsWithoutPopulationAreLeftOut() {
        List<Result> r = ScoreFormula.scoreAndRank(List.of(input(1, 0, 0, 0), input(2, 500, 100, 10)), "WALK");

        assertThat(ids(r)).containsExactly(2L);
        assertThat(ScoreFormula.scoreAndRank(List.of(), "WALK")).isEmpty();
    }

    @Test
    void zeroTargetGivesZeroFlowPointsWithoutShrink() {
        List<Result> r = ScoreFormula.scoreAndRank(List.of(input(1, 1000, 0, 0), input(2, 1000, 100, 50)), "WALK",
                NO_SHRINK);

        Result noTarget = r.get(1);
        assertThat(noTarget.input().boardId()).isEqualTo(1L);
        assertThat(noTarget.components().get(2).points()).isCloseTo(0, within(EPS));
    }

    @Test
    void minimumTargetExcludesSmallBoardsBeforeComparing() {
        ScoreFormula.Params params = new ScoreFormula.Params(40, 40, 20, 2.0, 50, 0.5, 10);
        List<Result> r = ScoreFormula.scoreAndRank(List.of(
                input(1, 100, 20, 5), input(2, 1000, 200, 50), input(3, 10000, 2000, 500)), "WALK", params);

        assertThat(ids(r)).containsExactly(3L, 2L);
        // 남은 후보끼리 log 척도를 다시 잡으므로 2번이 규모 0점
        assertThat(r.get(1).components().getFirst().points()).isCloseTo(0, within(EPS));
    }

    @Test
    void configuredPointsAreUsed() {
        ScoreFormula.Params params = new ScoreFormula.Params(60, 30, 10, 2.0, 0, 0.5, 10);
        Result r = ScoreFormula.scoreAndRank(List.of(input(1, 1000, 200, 50)), "WALK", params).getFirst();

        assertThat(r.score()).isCloseTo(60 + 15 + 5, within(EPS));
    }

    @Test
    void invalidParamsAreRejected() {
        assertThatThrownBy(() -> new ScoreFormula.Params(40, 40, 20, 1.0, 0, 0.5, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoreFormula.Params(0, 0, 20, 2.0, 0, 0.5, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoreFormula.Params(40, 40, 20, 2.0, 0, -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoreFormula.Params(40, 40, 20, 2.0, 0, 0.5, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void smallBoardRatioIsPulledTowardAverage() {
        // 작은 게시대(N 60)의 비율 0.5가 큰 게시대(N 2000)의 0.35보다 높지만, 보정하면 큰 쪽이 더 높게 평가된다
        List<Input> inputs = List.of(input(1, 60, 30, 10), input(2, 2000, 700, 200),
                input(3, 1000, 100, 30), input(4, 1000, 100, 30), input(5, 1000, 100, 30));
        List<Result> r = ScoreFormula.scoreAndRank(inputs, "WALK");
        Result small = r.stream().filter(x -> x.input().boardId() == 1L).findFirst().orElseThrow();
        Result big = r.stream().filter(x -> x.input().boardId() == 2L).findFirst().orElseThrow();

        // 화면용 원값은 보정 전 그대로
        assertThat(small.components().get(1).rawValue()).isCloseTo(0.5, within(EPS));
        assertThat(small.components().get(1).lift()).isLessThan(big.components().get(1).lift());

        // 보정을 끄면 작은 게시대 비율이 더 높게 평가된다
        ScoreFormula.Params off = new ScoreFormula.Params(40, 40, 20, 2.0, 0, 0, 10);
        List<Result> raw = ScoreFormula.scoreAndRank(inputs, "WALK", off);
        Result smallRaw = raw.stream().filter(x -> x.input().boardId() == 1L).findFirst().orElseThrow();
        Result bigRaw = raw.stream().filter(x -> x.input().boardId() == 2L).findFirst().orElseThrow();
        assertThat(smallRaw.components().get(1).lift()).isGreaterThan(bigRaw.components().get(1).lift());
    }

    @Test
    void medianOfEvenAndOddCounts() {
        assertThat(ScoreFormula.median(new double[] {3, 1, 2})).isCloseTo(2, within(EPS));
        assertThat(ScoreFormula.median(new double[] {4, 1, 2, 3})).isCloseTo(2.5, within(EPS));
    }

    @Test
    void tiesFollowFaceCountThenFeeThenId() {
        // 같은 인구 → 같은 점수. 게시면수↓ → 부착금액↑(없음 뒤) → ID↑
        List<Result> r = ScoreFormula.scoreAndRank(List.of(
                new Input(5, 3, 9000, 1000, 200, 50, 9, 9),
                new Input(4, 6, null, 1000, 200, 50, 9, 9),
                new Input(3, 6, 15000, 1000, 200, 50, 9, 9),
                new Input(2, null, 1000, 1000, 200, 50, 9, 9),
                new Input(1, 6, 15000, 1000, 200, 50, 9, 9)), "WALK");

        assertThat(ids(r)).containsExactly(1L, 3L, 4L, 5L, 2L);
    }
}
