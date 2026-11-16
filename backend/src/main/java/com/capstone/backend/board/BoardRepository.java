package com.capstone.backend.board;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.capstone.backend.common.ApiException;
import com.capstone.backend.common.Codes;

/**
 * board 테이블 조회. hasPopulationData는 board_population 행 존재 여부로 판단한다.
 */
@Repository
public class BoardRepository {

    private static final String SELECT = """
            SELECT b.*, EXISTS (SELECT 1 FROM board_population bp WHERE bp.board_id = b.board_id) AS has_population
            FROM board b
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BoardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 게시대 응답. 목록(마커)과 상세에 같은 형태를 쓴다(데모용 단순화). */
    public record Board(String boardId, String name, String city, String dong, String address,
                        double latitude, double longitude, String purpose, Integer faceCount,
                        Integer postingFee, String dataReferenceDate, boolean canInquire,
                        boolean hasPopulationData) {
    }

    public static long parseId(String boardId) {
        try {
            return Long.parseLong(boardId);
        } catch (NumberFormatException e) {
            throw boardNotFound();
        }
    }

    public List<Board> findAll(List<String> cities, List<String> purposes,
                               Double west, Double south, Double east, Double north) {
        List<String> where = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (!cities.isEmpty()) {
            List<String> dbCities = cities.stream().map(c -> {
                String db = Codes.CITY_TO_DB.get(c);
                if (db == null) {
                    throw ApiException.badRequest("알 수 없는 지역 코드입니다: " + c);
                }
                return db;
            }).toList();
            where.add("b.city IN (:cities)");
            params.addValue("cities", dbCities);
        }
        if (!purposes.isEmpty()) {
            for (String purpose : purposes) {
                if (!Codes.PURPOSES.contains(purpose)) {
                    throw ApiException.badRequest("알 수 없는 용도 코드입니다: " + purpose);
                }
            }
            where.add("b.purpose IN (:purposes)");
            params.addValue("purposes", purposes);
        }
        if (west != null && south != null && east != null && north != null) {
            where.add("b.longitude BETWEEN :west AND :east AND b.latitude BETWEEN :south AND :north");
            params.addValue("west", west).addValue("east", east).addValue("south", south).addValue("north", north);
        }
        String sql = SELECT + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where)) + " ORDER BY b.board_id";
        return jdbc.query(sql, params, (rs, i) -> map(rs));
    }

    public Board get(long boardId) {
        List<Board> found = jdbc.query(SELECT + " WHERE b.board_id = :id",
                new MapSqlParameterSource("id", boardId), (rs, i) -> map(rs));
        if (found.isEmpty()) {
            throw boardNotFound();
        }
        return found.getFirst();
    }

    private static ApiException boardNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "BOARD_NOT_FOUND", "게시대를 찾을 수 없습니다.");
    }

    private static Board map(ResultSet rs) throws SQLException {
        String purpose = rs.getString("purpose");
        return new Board(
                String.valueOf(rs.getLong("board_id")),
                rs.getString("name"),
                Codes.cityFromDb(rs.getString("city")),
                rs.getString("dong"),
                rs.getString("address"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                purpose,
                rs.getObject("face_count", Integer.class),
                rs.getObject("posting_fee", Integer.class),
                rs.getString("data_reference_date"),
                Codes.canInquire(purpose),
                rs.getBoolean("has_population"));
    }
}
