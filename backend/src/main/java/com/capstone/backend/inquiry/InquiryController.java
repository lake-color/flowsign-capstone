package com.capstone.backend.inquiry;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.backend.common.ApiResponse;
import com.capstone.backend.common.DatasetInfoReader;

/**
 * 광고 신청 문의 API (명세 4.7). 새 문의는 201, 같은 키·같은 내용 재전송은 기존 문의와 200.
 */
@RestController
@RequestMapping("/api/v1/inquiries")
public class InquiryController {

    private final InquiryService inquiries;
    private final DatasetInfoReader dataset;

    public InquiryController(InquiryService inquiries, DatasetInfoReader dataset) {
        this.inquiries = inquiries;
        this.dataset = dataset;
    }

    /** 헤더·본문이 빠져도 Spring 기본 오류(500)가 아니라 400 VALIDATION_ERROR가 되도록 서비스에서 검사한다. */
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) InquiryRequest request) {
        InquiryService.Result result = inquiries.create(idempotencyKey, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.of(result.data(), dataset.current()));
    }
}
