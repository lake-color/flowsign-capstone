package com.capstone.backend.cell;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.capstone.backend.common.DatasetInfo;
import com.capstone.backend.common.DatasetInfoReader;

/**
 * GET /api/v1/cells (명세 11장 데모 추가 API)의 범위 검사와 개수 제한을 DB 없이 검증한다.
 */
@WebMvcTest(CellController.class)
class CellControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    NamedParameterJdbcTemplate jdbc;

    @MockitoBean
    DatasetInfoReader dataset;

    @BeforeEach
    void setUp() {
        when(dataset.current()).thenReturn(DatasetInfo.UNLABELED);
    }

    private void dbReturns(List<CellController.CellCenter> cells) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<CellController.CellCenter>>any())).thenReturn(cells);
    }

    @Test
    void returnsCellsInBounds() throws Exception {
        dbReturns(List.of(
                new CellController.CellCenter("c1", 37.39, 126.96),
                new CellController.CellCenter("c2", 37.40, 126.97)));

        mockMvc.perform(get("/api/v1/cells")
                        .param("west", "126.9").param("south", "37.3")
                        .param("east", "127.0").param("north", "37.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cellCount").value(2))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.limit").value(CellController.MAX_CELLS))
                .andExpect(jsonPath("$.data.cells[0].cellId").value("c1"))
                .andExpect(jsonPath("$.data.cells[0].latitude").value(37.39));
    }

    @Test
    void tooManyCellsAreTruncated() throws Exception {
        dbReturns(IntStream.rangeClosed(0, CellController.MAX_CELLS)
                .mapToObj(i -> new CellController.CellCenter("c" + i, 37.0, 127.0))
                .toList());

        mockMvc.perform(get("/api/v1/cells")
                        .param("west", "126.0").param("south", "37.0")
                        .param("east", "127.5").param("north", "37.8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.cellCount").value(0))
                .andExpect(jsonPath("$.data.cells").isEmpty());
    }

    @Test
    void missingBoundIsValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/cells").param("west", "126.9").param("south", "37.3").param("east", "127.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(jdbc);
    }

    @Test
    void reversedBoundsAreValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/cells")
                        .param("west", "127.0").param("south", "37.3")
                        .param("east", "126.9").param("north", "37.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(jdbc);
    }

    @Test
    void zeroWidthBoundsAreValidationError() throws Exception {
        // 경계값: west == east (넓이 0)도 거절해야 한다
        mockMvc.perform(get("/api/v1/cells")
                        .param("west", "126.9").param("south", "37.3")
                        .param("east", "126.9").param("north", "37.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(jdbc);
    }

    @Test
    void exactlyLimitIsNotTruncated() throws Exception {
        // 경계값: 정확히 MAX_CELLS개는 잘리지 않는다
        dbReturns(IntStream.range(0, CellController.MAX_CELLS)
                .mapToObj(i -> new CellController.CellCenter("c" + i, 37.0, 127.0))
                .toList());

        mockMvc.perform(get("/api/v1/cells")
                        .param("west", "126.0").param("south", "37.0")
                        .param("east", "127.5").param("north", "37.8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.cellCount").value(CellController.MAX_CELLS));
    }

    @Test
    void nonNumericBoundIsValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/cells")
                        .param("west", "abc").param("south", "37.3")
                        .param("east", "127.0").param("north", "37.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(jdbc);
    }
}
