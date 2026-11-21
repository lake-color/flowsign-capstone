package com.capstone.backend.recommendation;

import java.util.List;

/**
 * 게시대 3×3 Cell의 가중치 (팀 초안 2026-11-11: 중심 0.8 + 주변 0.2, 교수님 피드백: 주변 8칸 차등).
 *
 * 주변 칸의 0.2는 게시대 위치에서 각 Cell 중심까지의 거리(board_cell.distance_meters)로 나눈다.
 * 가우스 감쇠 exp(-d² / 2σ²), σ = 250m(한 칸). 게시대가 중심 Cell의 북동쪽 끝에 있으면 북동쪽 이웃이
 * 더 무겁고 남서쪽 대각선 칸이 가장 가볍다. 같은 중심 Cell을 쓰는 게시대도 위치가 다르면 점수가 달라진다.
 *
 * 조건에 맞는 데이터가 없는 칸은 넘기지 않는다(명세 1.2: 없는 칸은 빼고 나머지로 계산).
 * 남은 칸의 가중치 합이 1이 되도록 맞추므로, 가중 합은 '대표 Cell 하나의 값'에 해당하는 지수다.
 * 상세 화면의 3×3 합계와는 크기가 다르다.
 *
 * 비교 근거(균등·거리 반비례·가우스): python/src/data_processing/simulate.py E4.
 */
public final class CellWeights {

    public static final double CENTER_WEIGHT = 0.8;
    public static final double NEIGHBOR_SIGMA_METERS = 250.0;

    private CellWeights() {
    }

    /** distanceMeters: 게시대 위경도 → Cell 중심 거리 */
    public record Cell(boolean center, double distanceMeters) {
    }

    /** 기본값(중심 0.8, σ 250m)으로 계산한다. */
    public static double[] of(List<Cell> cells) {
        return of(cells, CENTER_WEIGHT, NEIGHBOR_SIGMA_METERS);
    }

    /**
     * cells 순서대로 가중치를 돌려준다. 합은 1 (cells가 비어 있으면 빈 배열).
     * centerWeight·sigmaMeters는 설정(flowsign.recommendation.*)으로 바꿀 수 있다 (PRD N15).
     */
    public static double[] of(List<Cell> cells, double centerWeight, double sigmaMeters) {
        if (centerWeight < 0 || centerWeight > 1 || sigmaMeters <= 0) {
            throw new IllegalArgumentException("centerWeight는 0~1, sigmaMeters는 0보다 커야 합니다.");
        }
        double[] weights = new double[cells.size()];
        double kernelSum = 0;
        for (Cell cell : cells) {
            if (!cell.center()) {
                kernelSum += kernel(cell.distanceMeters(), sigmaMeters);
            }
        }
        double total = 0;
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i);
            if (cell.center()) {
                weights[i] = centerWeight;
            } else {
                double k = kernel(cell.distanceMeters(), sigmaMeters);
                weights[i] = kernelSum > 0 ? (1 - centerWeight) * k / kernelSum : 0;
            }
            total += weights[i];
        }
        for (int i = 0; i < weights.length; i++) {
            weights[i] = total > 0 ? weights[i] / total : 0;
        }
        return weights;
    }

    static double kernel(double distanceMeters, double sigmaMeters) {
        return Math.exp(-(distanceMeters * distanceMeters) / (2 * sigmaMeters * sigmaMeters));
    }
}
