package com.capstone.backend.common;

import java.util.UUID;

/**
 * 성공 응답 공통 형태 {@code {data, meta}} (API 명세 2.1).
 */
public record ApiResponse<T>(T data, Meta meta) {

    public record Meta(String requestId, String datasetVersion, boolean syntheticData) {
    }

    public static <T> ApiResponse<T> of(T data, DatasetInfo dataset) {
        return new ApiResponse<>(data, new Meta(newRequestId(), dataset.version(), dataset.synthetic()));
    }

    static String newRequestId() {
        return "req-" + UUID.randomUUID();
    }
}
