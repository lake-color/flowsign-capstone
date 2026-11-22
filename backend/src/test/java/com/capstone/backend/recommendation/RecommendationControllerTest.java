package com.capstone.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.capstone.backend.common.ApiException;
import com.capstone.backend.common.DatasetInfo;
import com.capstone.backend.common.DatasetInfoReader;

/**
 * POST /api/v1/recommendations/search (명세 4.6)의 본문 해석·응답 형태·오류 코드를 DB 없이 검증한다.
 */
@WebMvcTest(RecommendationController.class)
class RecommendationControllerTest {

    private static final String BODY = """
            {"criteria": {"cities": ["ANYANG"], "gender": "FEMALE", "ageGroups": ["AGE_20S", "AGE_30S"],
                          "flowType": "WALK", "timeBands": ["16_18", "18_20"],
                          "period": {"kind": "SEASON", "year": 2025, "season": "SUMMER"},
                          "dayType": "ALL", "purposes": ["COMMERCIAL", "MIXED"]},
             "page": 1, "pageSize": 10}
            """;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RecommendationService service;

    @MockitoBean
    DatasetInfoReader dataset;

    @BeforeEach
    void setUp() {
        when(dataset.current()).thenReturn(DatasetInfo.UNLABELED);
    }

    @Test
    void readsBodyAndWrapsResult() throws Exception {
        when(service.search(any())).thenReturn(Map.of("items", List.of(), "totalItems", 0));

        mockMvc.perform(post("/api/v1/recommendations/search").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.meta.requestId").value(startsWith("req-")))
                .andExpect(jsonPath("$.meta.syntheticData").value(true));

        ArgumentCaptor<RecommendationRequest> captor = ArgumentCaptor.forClass(RecommendationRequest.class);
        verify(service).search(captor.capture());
        RecommendationRequest request = captor.getValue();
        assertThat(request.page()).isEqualTo(1);
        assertThat(request.criteria().ageGroups()).containsExactly("AGE_20S", "AGE_30S");
        assertThat(request.criteria().period().season()).isEqualTo("SUMMER");
    }

    @Test
    void malformedJsonIsValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/recommendations/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"criteria\": {\"period\": {\"month\": \"abc\"}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(service);
    }

    @Test
    void serviceErrorsKeepTheirCode() throws Exception {
        when(service.search(any())).thenThrow(
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DATA_NOT_READY", "인구 집계 데이터가 아직 적재되지 않았습니다."));

        mockMvc.perform(post("/api/v1/recommendations/search").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DATA_NOT_READY"));
    }
}
