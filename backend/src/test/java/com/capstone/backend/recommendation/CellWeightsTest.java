package com.capstone.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.capstone.backend.recommendation.CellWeights.Cell;

/**
 * 3×3 Cell 가중치: 중심 0.8 + 주변 0.2를 거리로 차등, 없는 칸은 빼고 합 1로 맞춤.
 */
class CellWeightsTest {

    private static final double EPS = 1e-9;

    @Test
    void fullThreeByThreeGivesCenterPointEightAndNeighborsPointTwo() {
        List<Cell> cells = List.of(new Cell(true, 50), new Cell(false, 200), new Cell(false, 210),
                new Cell(false, 260), new Cell(false, 290), new Cell(false, 300), new Cell(false, 340),
                new Cell(false, 380), new Cell(false, 450));

        double[] w = CellWeights.of(cells);

        assertThat(w[0]).isCloseTo(0.8, within(EPS));
        assertThat(Arrays.stream(w).sum()).isCloseTo(1.0, within(EPS));
        assertThat(Arrays.stream(w, 1, w.length).sum()).isCloseTo(0.2, within(EPS));
    }

    @Test
    void closerNeighborWeighsMore() {
        double[] w = CellWeights.of(List.of(new Cell(true, 100), new Cell(false, 150), new Cell(false, 450)));

        assertThat(w[1]).isGreaterThan(w[2]);
        assertThat(w[1] + w[2]).isCloseTo(0.2, within(EPS));
    }

    @Test
    void neighborsAtSameDistanceShareEqually() {
        double[] w = CellWeights.of(List.of(new Cell(true, 0), new Cell(false, 250), new Cell(false, 250)));

        assertThat(w[1]).isCloseTo(0.1, within(EPS));
        assertThat(w[2]).isCloseTo(0.1, within(EPS));
    }

    @Test
    void centerAloneGetsEverything() {
        assertThat(CellWeights.of(List.of(new Cell(true, 80)))[0]).isCloseTo(1.0, within(EPS));
    }

    @Test
    void missingCenterRescalesNeighborsToOne() {
        double[] w = CellWeights.of(List.of(new Cell(false, 200), new Cell(false, 200)));

        assertThat(w[0]).isCloseTo(0.5, within(EPS));
        assertThat(w[1]).isCloseTo(0.5, within(EPS));
    }

    @Test
    void configuredCenterWeightIsUsed() {
        double[] w = CellWeights.of(List.of(new Cell(true, 0), new Cell(false, 250), new Cell(false, 250)), 0.6, 250);

        assertThat(w[0]).isCloseTo(0.6, within(EPS));
        assertThat(w[1]).isCloseTo(0.2, within(EPS));
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> CellWeights.of(List.of(), 1.2, 250)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CellWeights.of(List.of(), 0.8, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noCellsGivesEmptyWeights() {
        assertThat(CellWeights.of(List.of())).isEmpty();
    }
}
