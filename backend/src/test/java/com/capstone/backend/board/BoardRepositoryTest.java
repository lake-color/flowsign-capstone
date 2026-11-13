package com.capstone.backend.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.capstone.backend.common.ApiException;

/**
 * 게시대 목록 조건 검사와 WHERE 절 구성 (명세 2장, 4.2). DB 대신 mock JdbcTemplate을 쓴다.
 */
class BoardRepositoryTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final BoardRepository repository = new BoardRepository(jdbc);

    @Test
    void parseIdAcceptsNumber() {
        assertThat(BoardRepository.parseId("42")).isEqualTo(42L);
    }

    @Test
    void parseIdRejectsNonNumberAsBoardNotFound() {
        assertThatThrownBy(() -> BoardRepository.parseId("abc"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.code()).isEqualTo("BOARD_NOT_FOUND");
                });
    }

    @Test
    void unknownCityIsValidationError() {
        assertThatThrownBy(() -> repository.findAll(List.of("SEOUL"), List.of(), null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void unknownPurposeIsValidationError() {
        assertThatThrownBy(() -> repository.findAll(List.of(), List.of("PARKING"), null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_ERROR"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void noFilterQueriesAllBoards() {
        repository.findAll(List.of(), List.of(), null, null, null, null);

        assertThat(capturedSql()).doesNotContain("WHERE b.").endsWith("ORDER BY b.board_id");
    }

    @Test
    void cityCodesAreConvertedToDbValues() {
        repository.findAll(List.of("ANYANG", "SIHEUNG"), List.of("COMMERCIAL"), null, null, null, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), ArgumentMatchers.<RowMapper<BoardRepository.Board>>any());

        assertThat(sql.getValue()).contains("b.city IN (:cities)").contains("b.purpose IN (:purposes)");
        assertThat(params.getValue().getValue("cities")).isEqualTo(List.of("안양시", "시흥시"));
        assertThat(params.getValue().getValue("purposes")).isEqualTo(List.of("COMMERCIAL"));
    }

    @Test
    void bboxIsUsedOnlyWhenAllFourGiven() {
        repository.findAll(List.of(), List.of(), 126.9, 37.3, 127.0, 37.5);
        assertThat(capturedSql()).contains("b.longitude BETWEEN :west AND :east");
    }

    @Test
    void partialBboxIsIgnored() {
        repository.findAll(List.of(), List.of(), 126.9, null, null, null);
        assertThat(capturedSql()).doesNotContain("b.longitude");
    }

    private String capturedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), ArgumentMatchers.any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<BoardRepository.Board>>any());
        return sql.getValue();
    }
}
