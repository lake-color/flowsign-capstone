package com.capstone.backend.inquiry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.capstone.backend.common.ApiException;
import com.capstone.backend.common.DatasetInfo;
import com.capstone.backend.common.DatasetInfoReader;

/**
 * POST /api/v1/inquiries (명세 4.7)의 HTTP 상태(201/200)와 헤더·본문 전달을 DB 없이 검증한다.
 * 값 검사·중복 방지는 InquiryIntegrationTest(실제 DB)에서 검증한다.
 */
@WebMvcTest(InquiryController.class)
class InquiryControllerTest {

    static final String KEY = "3f1c2b9e-8d4a-4f6b-9c2e-1a7d5e8f0b3c";
    static final String BODY = """
            {"boardId": "1", "desiredPosting": {"year": 2026, "month": 12, "half": "FIRST"},
             "industry": "EDUCATION", "contact": "demo@example.com",
             "privacyConsent": true, "privacyConsentVersion": "demo-v1"}
            """;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    InquiryService service;

    @MockitoBean
    DatasetInfoReader dataset;

    @BeforeEach
    void setUp() {
        when(dataset.current()).thenReturn(DatasetInfo.UNLABELED);
    }

    @Test
    void newInquiryIs201() throws Exception {
        when(service.create(eq(KEY), any())).thenReturn(new InquiryService.Result(true, Map.of("inquiryId", "7")));

        mockMvc.perform(post("/api/v1/inquiries").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.inquiryId").value("7"))
                .andExpect(jsonPath("$.meta.syntheticData").value(true));
    }

    @Test
    void replayIs200() throws Exception {
        when(service.create(eq(KEY), any())).thenReturn(new InquiryService.Result(false, Map.of("inquiryId", "7")));

        mockMvc.perform(post("/api/v1/inquiries").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inquiryId").value("7"));
    }

    @Test
    void missingHeaderReachesServiceAsNull() throws Exception {
        // 헤더가 없어도 Spring 기본 오류(500)가 아니라 서비스 검사(400)로 가야 한다
        when(service.create(isNull(), any())).thenThrow(ApiException.badRequest("Idempotency-Key 헤더가 필요합니다."));

        mockMvc.perform(post("/api/v1/inquiries").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(service).create(isNull(), any());
    }

    @Test
    void malformedJsonIsValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/inquiries").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"desiredPosting\": {\"month\": \"abc\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }
}
