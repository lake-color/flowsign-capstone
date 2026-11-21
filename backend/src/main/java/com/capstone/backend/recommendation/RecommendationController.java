package com.capstone.backend.recommendation;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.backend.common.ApiResponse;
import com.capstone.backend.common.DatasetInfoReader;

/**
 * 추천 API (API 명세 4.6). '추천 분석하기' 버튼을 눌렀을 때만 호출한다.
 */
@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {

    private final RecommendationService recommendations;
    private final DatasetInfoReader dataset;

    public RecommendationController(RecommendationService recommendations, DatasetInfoReader dataset) {
        this.recommendations = recommendations;
        this.dataset = dataset;
    }

    @PostMapping("/search")
    public ApiResponse<Map<String, Object>> search(@RequestBody(required = false) RecommendationRequest request) {
        return ApiResponse.of(recommendations.search(request), dataset.current());
    }
}
