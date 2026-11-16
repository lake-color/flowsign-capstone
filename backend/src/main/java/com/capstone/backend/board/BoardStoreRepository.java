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
 * 상세 패널의 개수와 지도 레이어에 그리는 상가가 항상 같은 집합이다.
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

    /** 조회 한 행: 응답용 상가 + 기준월(응답에서는 상가마다가 아니라 한 번만 내려준다) */
    private record Row(Store store, String baseMonth) {
    }

    /** 게시대 3×3 Cell(board_cell)에 속한 상가와 기준월. 상가가 없으면 기준월은 null이다. */
    public Map<String, Object> stores(long boardId) {
        List<Row> rows = jdbc.query("""
                SELECT s.store_id, s.name, s.category, s.sub_category_name, s.latitude, s.longitude, s.base_month
                FROM store s JOIN board_cell bc ON bc.cell_id = s.cell_id
                WHERE bc.board_id = :id
                ORDER BY s.store_id
                """, new MapSqlParameterSource("id", boardId), (rs, i) -> new Row(
                new Store(rs.getString("store_id"), rs.getString("name"), rs.getString("category"),
                        rs.getString("sub_category_name"), rs.getDouble("latitude"), rs.getDouble("longitude")),
                rs.getString("base_month")));
        List<Store> stores = rows.stream().map(Row::store).toList();
        String baseMonth = rows.stream().map(Row::baseMonth).max(Comparator.naturalOrder()).orElse(null);

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
