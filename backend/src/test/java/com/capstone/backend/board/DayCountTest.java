package com.capstone.backend.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * 일평균용 일수 계산 (달력 기준 주중=월~금, 주말=토·일). 2025년 달력으로 검증한다.
 */
class DayCountTest {

    @Test
    void january2025() {
        List<String> jan = List.of("202501");
        assertThat(DayCount.of(jan, "ALL")).isEqualTo(31);
        assertThat(DayCount.of(jan, "WEEKDAY")).isEqualTo(23);
        assertThat(DayCount.of(jan, "WEEKEND")).isEqualTo(8);
    }

    @Test
    void february2025HasTwentyEightDays() {
        List<String> feb = List.of("202502");
        assertThat(DayCount.of(feb, "ALL")).isEqualTo(28);
        assertThat(DayCount.of(feb, "WEEKDAY")).isEqualTo(20);
        assertThat(DayCount.of(feb, "WEEKEND")).isEqualTo(8);
    }

    @Test
    void wholeYear2025() {
        List<String> year = IntStream.rangeClosed(1, 12).mapToObj(m -> String.format("2025%02d", m)).toList();
        assertThat(DayCount.of(year, "ALL")).isEqualTo(365);
        assertThat(DayCount.of(year, "WEEKDAY")).isEqualTo(261);
        assertThat(DayCount.of(year, "WEEKEND")).isEqualTo(104);
    }

    @Test
    void winterAddsDaysOfEachMonth() {
        List<String> winter = List.of("202501", "202502", "202512");
        assertThat(DayCount.of(winter, "ALL")).isEqualTo(31 + 28 + 31);
        assertThat(DayCount.of(winter, "WEEKDAY")).isEqualTo(23 + 20 + 23);
    }

    @Test
    void perDayKeepsNullAndDivides() {
        assertThat(DayCount.perDay(null, 31)).isNull();
        assertThat(DayCount.perDay(0.0, 31)).isEqualTo(0.0);
        assertThat(DayCount.perDay(310.0, 31)).isEqualTo(10.0);
    }
}
