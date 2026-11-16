package com.capstone.backend.common;

/**
 * 인구 수치 (API 명세 2.1). 0과 null을 구분한다.
 * 값은 선택 기간의 일평균이므로 unitLabel="명/일" (팀 결정 2026-11-16).
 * 마스킹·공개 가능 여부(L5)가 확인되지 않아 값이 있어도 status=UNVERIFIED.
 */
public record PopulationValue(Double value, String status, String unitLabel) {

    public static final String UNIT_LABEL = "명/일";

    public static PopulationValue of(Double value) {
        if (value == null) {
            return new PopulationValue(null, "MISSING", null);
        }
        return new PopulationValue(Math.round(value * 10) / 10.0, "UNVERIFIED", UNIT_LABEL);
    }
}
