package com.capstone.backend.meta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.backend.common.ApiResponse;
import com.capstone.backend.common.Codes;
import com.capstone.backend.common.DatasetInfo;

/**
 * GET /api/v1/meta — 필터 선택지, 격자 기준값, 데이터 준비 상태 (API 명세 4.1).
 */
@RestController
@RequestMapping("/api/v1")
public class MetaController {

    private final JdbcTemplate jdbc;
    private final DatasetInfo dataset;

    public MetaController(JdbcTemplate jdbc, DatasetInfo dataset) {
        this.jdbc = jdbc;
        this.dataset = dataset;
    }

    public record Grid(int cellSizeMeters, double originLatitude, double originLongitude,
                       double latitudeStepDeg, double longitudeStepDeg, String status) {
    }

    @GetMapping("/meta")
    public ApiResponse<Map<String, Object>> meta() {
        Grid grid = jdbc.query("SELECT * FROM grid_spec WHERE id = 1", rs -> rs.next()
                ? new Grid(rs.getInt("cell_size_meters"), rs.getDouble("origin_latitude"),
                        rs.getDouble("origin_longitude"), rs.getDouble("latitude_step_deg"),
                        rs.getDouble("longitude_step_deg"), rs.getString("status"))
                : null);

        List<String> dayTypes = new ArrayList<>(List.of("ALL"));
        dayTypes.addAll(jdbc.queryForList(
                "SELECT DISTINCT day_type FROM board_population WHERE day_type IN ('WEEKDAY', 'WEEKEND') ORDER BY day_type DESC",
                String.class));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serviceName", "FLOWSIGN");
        data.put("dataYear", Codes.DATA_YEAR);
        data.put("cities", Codes.CITIES);
        data.put("timeBands", Codes.TIME_BANDS);
        data.put("availableDayTypes", dayTypes);
        data.put("dayTypeSourceStatus", dayTypes.size() > 1 ? "VERIFIED" : "UNCONFIRMED");
        data.put("cellSizeMeters", grid == null ? 250 : grid.cellSizeMeters());
        data.put("influenceArea", "CELL_3X3");
        data.put("grid", grid);
        data.put("populationUnitLabel", null);
        data.put("populationUnitStatus", "UNCONFIRMED");
        data.put("combinationRuleStatus", "UNCONFIRMED");
        data.put("algorithmStatus", "PROVISIONAL");
        data.put("algorithmVersion", "provisional-v1");
        data.put("recommendationAvailable", false);
        data.put("maxRecommendationPageSize", 50);
        data.put("mapScope", "BOARD_ONLY");
        data.put("notice", "통신사 유동인구를 활용한 노출 가능성 추정이며 실제 광고 성과를 의미하지 않습니다.");
        return ApiResponse.of(data, dataset);
    }
}
