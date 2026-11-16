package com.capstone.backend.board;

import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.List;

/**
 * 인구값 일평균 계산용 일수 (팀 결정 2026-11-16: 인구값은 선택 기간의 일평균으로 표시).
 *
 * 원본 월 값은 그 달 주중/주말 각각의 합계다(명세 9장 L3, 실제 원본으로 확인).
 * 따라서 일평균 = 기간·요일 구분에 해당하는 합계 ÷ 그 기간의 해당 일수.
 * 주중 = 월~금, 주말 = 토·일 (달력 기준). 원본의 공휴일 구분 기준은 확인되지 않아 반영하지 않는다.
 *
 * 추천 등 다른 기능에서 인구 수치를 보여줄 때도 같은 기준을 쓰도록 public으로 둔다.
 */
public final class DayCount {

    private DayCount() {
    }

    /** months(YYYYMM) 안에서 dayType(ALL / WEEKDAY / WEEKEND)에 해당하는 날 수. */
    public static int of(List<String> months, String dayType) {
        int days = 0;
        for (String ym : months) {
            YearMonth month = YearMonth.of(Integer.parseInt(ym.substring(0, 4)), Integer.parseInt(ym.substring(4, 6)));
            for (int d = 1; d <= month.lengthOfMonth(); d++) {
                DayOfWeek w = month.atDay(d).getDayOfWeek();
                boolean weekend = w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
                if (dayType.equals("ALL") || dayType.equals("WEEKEND") == weekend) {
                    days++;
                }
            }
        }
        return days;
    }

    /** 합계 → 일평균. 값 없음(null)은 0으로 바꾸지 않고 null 그대로 둔다. */
    public static Double perDay(Double sum, int days) {
        return sum == null ? null : sum / days;
    }
}
