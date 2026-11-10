package com.capstone.backend.common;

/**
 * 응답 meta에 넣을 데이터셋 정보. 값은 {@link DatasetInfoReader}가 DB에서 읽는다.
 *
 * @param version   적재된 세트 이름과 적재 날짜 (예: fake-20260101). 적재 기록이 없으면 null
 * @param synthetic 가짜 데이터 여부
 */
public record DatasetInfo(String version, boolean synthetic) {

    /** 적재 기록이 없을 때. 실제 데이터로 오해하지 않도록 가짜로 간주한다. */
    public static final DatasetInfo UNLABELED = new DatasetInfo(null, true);

    private static final String UNIT_NOTICE = "인구 단위 확인 전이므로 수치는 기간 내 합산 참고값입니다.";

    /** 인구 응답에 붙이는 안내 문구. */
    public String populationNotice() {
        if (version == null) {
            return "데이터 종류가 기록되지 않아 가짜 데이터로 간주합니다. " + UNIT_NOTICE;
        }
        return (synthetic ? "가짜 데이터 기반 데모입니다. " : "") + UNIT_NOTICE;
    }
}
