package com.capstone.backend.common;

import java.util.List;
import java.util.Map;

/**
 * API 코드값 목록과 DB 값 변환 (API 명세 2장).
 */
public final class Codes {

    private Codes() {
    }

    /** API city 코드 ↔ DB city 값 */
    public static final Map<String, String> CITY_TO_DB = Map.of(
            "ANYANG", "안양시",
            "GUNPO", "군포시",
            "SIHEUNG", "시흥시");

    public static final List<String> CITIES = List.of("ANYANG", "GUNPO", "SIHEUNG");
    public static final List<String> AGE_GROUPS = List.of(
            "UNDER_20", "AGE_20S", "AGE_30S", "AGE_40S", "AGE_50S", "AGE_60_PLUS");
    public static final List<String> TIME_BANDS = List.of(
            "06_08", "08_10", "10_12", "12_14", "14_16", "16_18", "18_20");
    public static final List<String> PURPOSES = List.of("COMMERCIAL", "MIXED", "ADMINISTRATIVE");
    public static final int DATA_YEAR = 2025;

    public static String cityFromDb(String dbCity) {
        return CITY_TO_DB.entrySet().stream()
                .filter(e -> e.getValue().equals(dbCity))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    public static boolean canInquire(String purpose) {
        return !"ADMINISTRATIVE".equals(purpose);
    }
}
