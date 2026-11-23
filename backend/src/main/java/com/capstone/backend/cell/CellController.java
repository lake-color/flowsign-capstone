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
import com.capstone.backend.common.DatasetInfoReader;

/**
 * 지도 Cell 레이어용 Cell 중심 좌표. 화면 범위 안만 돌려준다.
 * <ul>
 *   <li>GET /api/v1/cells — 데이터에 있는 통신사 Cell 전체</li>
 *   <li>GET /api/v1/board-cells — 어느 게시대든 그 3×3에 속한 Cell만 (board_cell 기준, 중복 없이)</li>
 * </ul>
 * 인구값은 담지 않는다(전체 격자 인구는 확장 기능). 사각형은 프론트가 Cell 중심 ±125m로 그린다.
 * 화면 범위가 넓어 Cell이 너무 많으면 목록 없이 truncated=true만 돌려준다(확대 안내용).
 */
@RestController
@RequestMapping("/api/v1")
public class CellController {

    /** 한 번에 내려줄 최대 Cell 수 (지도 성능 보호, 임시값) */
    static final int MAX_CELLS = 3000;

    private final NamedParameterJdbcTemplate jdbc;
    private final DatasetInfoReader dataset;

    public CellController(NamedParameterJdbcTemplate jdbc, DatasetInfoReader dataset) {
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
        return inView("""
                SELECT cell_id, latitude, longitude FROM cell
                WHERE latitude BETWEEN :south AND :north AND longitude BETWEEN :west AND :east
                LIMIT :limit
                """, west, south, east, north);
    }

    @GetMapping("/board-cells")
    public ApiResponse<Map<String, Object>> boardCells(
            @RequestParam(required = false) Double west,
            @RequestParam(required = false) Double south,
            @RequestParam(required = false) Double east,
            @RequestParam(required = false) Double north) {
        // 여러 게시대의 3×3이 겹치면 같은 Cell이 여러 번 나오므로 한 번만 돌려준다.
        return inView("""
                SELECT c.cell_id, c.latitude, c.longitude FROM cell c
                WHERE c.latitude BETWEEN :south AND :north AND c.longitude BETWEEN :west AND :east
                  AND EXISTS (SELECT 1 FROM board_cell bc WHERE bc.cell_id = c.cell_id)
                LIMIT :limit
                """, west, south, east, north);
    }

    /** 범위를 검사하고 sql(:west·:south·:east·:north·:limit 사용)로 화면 범위 안 Cell을 찾는다. */
    private ApiResponse<Map<String, Object>> inView(String sql, Double west, Double south, Double east, Double north) {
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
        List<CellCenter> found = jdbc.query(sql, p,
                (rs, i) -> new CellCenter(rs.getString("cell_id"), rs.getDouble("latitude"), rs.getDouble("longitude")));

        boolean truncated = found.size() > MAX_CELLS;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cells", truncated ? List.of() : found);
        data.put("cellCount", truncated ? 0 : found.size());
        data.put("truncated", truncated);
        data.put("limit", MAX_CELLS);
        return ApiResponse.of(data, dataset.current());
    }
}
