package com.capstone.backend.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 응답 meta에 넣을 데이터셋 정보. 가짜 데이터로 시연할 때는 synthetic=true.
 */
@ConfigurationProperties(prefix = "flowsign.dataset")
public record DatasetInfo(String version, boolean synthetic) {
}
