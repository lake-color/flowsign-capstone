package com.capstone.backend.board;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 게시대 3×3 Cell 안의 상가 (store 테이블). 업종별 개수는 같은 상가 목록에서 세므로
 * 상세 패널의 개수와 지도 레이어의 점 개수가 항상 같다.
 */
@Repository
public class BoardStoreRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public BoardStoreRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Store(String storeId, String name, String category, String subCategoryName,
                        double latitude, double longitude) {
    }

    public record CategoryCount(String category, int count) {
    }

    /** 게시대 3×3 Cell(board_cell)에 속한 상가와 기준월. 상가가 없으면 기준월은 null이다. */
    public Map<String, Object> stores(long boardId) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", boardId);
        List<Store> stores = jdbc.query("""
                SELECT s.store_id, s.name, s.category, s.sub_category_name, s.latitude, s.longitude
                FROM store s JOIN board_cell bc ON bc.cell_id = s.cell_id
                WHERE bc.board_id = :id
                ORDER BY s.store_id
                """, p, (rs, i) -> new Store(rs.getString("store_id"), rs.getString("name"), rs.getString("category"),
                rs.getString("sub_category_name"), rs.getDouble("latitude"), rs.getDouble("longitude")));
        String baseMonth = jdbc.queryForObject("""
                SELECT MAX(s.base_month) FROM store s JOIN board_cell bc ON bc.cell_id = s.cell_id
                WHERE bc.board_id = :id
                """, p, String.class);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("baseMonth", baseMonth);
        data.put("totalCount", stores.size());
        data.put("categories", countByCategory(stores));
        data.put("stores", stores);
        return data;
    }

    /** 업종별 개수. 많은 순, 같으면 코드 순. 0건인 업종은 넣지 않는다. */
    static List<CategoryCount> countByCategory(List<Store> stores) {
        return stores.stream()
                .collect(Collectors.groupingBy(Store::category, Collectors.counting()))
                .entrySet().stream()
                .map(e -> new CategoryCount(e.getKey(), e.getValue().intValue()))
                .sorted(Comparator.comparingInt(CategoryCount::count).reversed().thenComparing(CategoryCount::category))
                .toList();
    }
}
