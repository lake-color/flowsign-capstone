package com.capstone.backend.health;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서버가 요청을 받을 수 있는 상태인지 확인하는 API.
 *
 * Frontend와 Backend를 연결할 때 가장 먼저 호출해 보는 용도이며,
 * DB나 외부 연동 상태는 확인하지 않는다.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
