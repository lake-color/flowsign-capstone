package com.capstone.backend.common;

/**
 * 인구 수치 (API 명세 2.1). 0과 null을 구분한다.
 * 인구 단위(월평균/합계)가 아직 확인되지 않았으므로 값이 있어도 status=UNVERIFIED, unitLabel=null.
 */
public record PopulationValue(Double value, String status, String unitLabel) {

    public static PopulationValue of(Double value) {
        if (value == null) {
            return new PopulationValue(null, "MISSING", null);
        }
        return new PopulationValue(Math.round(value * 10) / 10.0, "UNVERIFIED", null);
    }
}
