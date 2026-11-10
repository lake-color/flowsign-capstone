package com.capstone.backend.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 3×3 Cell 사각형이 격자 검증과 관계없이 중심 ±125m(한 변 250m)로 만들어지는지 확인한다. DB 불필요.
 */
class CellSquareTest {

    private static final double METERS_PER_DEG_LAT = 111_320.0;

    @SuppressWarnings("unchecked")
    private static List<List<Double>> ring(Map<String, Object> polygon) {
        return ((List<List<List<Double>>>) polygon.get("coordinates")).get(0);
    }

    @Test
    void squareIs250MetersAroundCenter() {
        double lat = 37.4, lng = 126.9;
        Map<String, Object> polygon = BoardPopulationService.square(lat, lng);
        List<List<Double>> ring = ring(polygon);

        assertThat(polygon.get("type")).isEqualTo("Polygon");
        assertThat(ring).hasSize(5);
        assertThat(ring.get(0)).isEqualTo(ring.get(4)); // 닫힌 고리

        double west = ring.get(0).get(0), south = ring.get(0).get(1);
        double east = ring.get(2).get(0), north = ring.get(2).get(1);
        double heightMeters = (north - south) * METERS_PER_DEG_LAT;
        double widthMeters = (east - west) * METERS_PER_DEG_LAT * Math.cos(Math.toRadians(lat));

        assertThat(heightMeters).isCloseTo(250.0, within(0.01));
        assertThat(widthMeters).isCloseTo(250.0, within(0.01));
        assertThat((west + east) / 2).isCloseTo(lng, within(1e-9));
        assertThat((south + north) / 2).isCloseTo(lat, within(1e-9));
    }
}
