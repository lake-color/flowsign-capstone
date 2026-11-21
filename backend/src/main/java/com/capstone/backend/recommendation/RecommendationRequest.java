package com.capstone.backend.recommendation;

import java.util.List;

/**
 * POST /api/v1/recommendations/search 요청 본문 (API 명세 4.6, 공통 필터 Criteria는 2.1).
 * 값 검사는 {@link RecommendationService}가 조회 API와 같은 규칙으로 한다.
 *
 * @param page     0부터. 없으면 0
 * @param pageSize 없으면 10
 */
public record RecommendationRequest(Criteria criteria, Integer page, Integer pageSize) {

    public record Criteria(List<String> cities, String gender, List<String> ageGroups, String flowType,
                           List<String> timeBands, Period period, String dayType, List<String> purposes) {
    }

    /** kind: YEAR / SEASON / MONTH. season은 SEASON일 때, month는 MONTH일 때만 쓴다. */
    public record Period(String kind, Integer year, String season, Integer month) {
    }
}
