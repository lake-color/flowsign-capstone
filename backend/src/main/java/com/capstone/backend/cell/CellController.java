package com.capstone.backend.cell;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.backend.common.ApiException;
import com.capstone.backend.common.ApiResponse;
import com.capstone.backend.common.DatasetInfo;

/**
 * GET /api/v1/cells — 지도 화면 범위 안의 통신사 Cell 중심 좌표 (지도 Cell 레이어용).
 *
 * 인구값은 담지 않는다(전체 격자 인구는 확장 기능). 사각형은 프론트가 /meta.grid 간격으로 그린다.
 * 화면 범위가 넓어 Cell이 너무 많으면 목록 없이 truncated=true만 돌려준다(확대 안내용).
 */
@RestController
@RequestMapping("/api/v1")
public class CellController {

    /** 한 번에 내려줄 최대 Cell 수 (지도 성능 보호, 임시값) */
    static final int MAX_CELLS = 3000;

    private final NamedParameterJdbcTemplate jdbc;
    private final DatasetInfo dataset;

    public CellController(NamedParameterJdbcTemplate jdbc, DatasetInfo dataset) {
        this.jdbc = jdbc;
        this.dataset = dataset;
    }

    public record CellCenter(String cellId, double latitude, double longitude) {
    }

    @GetMapping("/cells")
    public ApiResponse<Map<String, Object>> cells(
            @RequestParam(required = false) Double west,
            @RequestParam(required = false) Double south,
            @RequestParam(required = false) Double east,
            @RequestParam(required = false) Double north) {
        if (west == null || south == null || east == null || north == null) {
            throw ApiException.badRequest("west, south, east, north가 모두 필요합니다.");
        }
        if (west >= east || south >= north) {
            throw ApiException.badRequest("화면 범위가 올바르지 않습니다 (west < east, south < north).");
        }

        // 중심은 화면 밖이지만 사각형이 화면에 걸친 Cell도 보이도록, 반 칸(125m)보다 넉넉하게 넓혀 찾는다.
        double margin = 0.002; // 위도 약 220m, 경도 약 180m
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("west", west - margin).addValue("east", east + margin)
                .addValue("south", south - margin).addValue("north", north + margin)
                .addValue("limit", MAX_CELLS + 1);
        List<CellCenter> found = jdbc.query("""
                SELECT cell_id, latitude, longitude FROM cell
                WHERE latitude BETWEEN :south AND :north AND longitude BETWEEN :west AND :east
                LIMIT :limit
                """, p, (rs, i) -> new CellCenter(rs.getString("cell_id"), rs.getDouble("latitude"), rs.getDouble("longitude")));

        boolean truncated = found.size() > MAX_CELLS;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cells", truncated ? List.of() : found);
        data.put("cellCount", truncated ? 0 : found.size());
        data.put("truncated", truncated);
        data.put("limit", MAX_CELLS);
        return ApiResponse.of(data, dataset);
    }
}
