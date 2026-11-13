package com.capstone.backend.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.capstone.backend.common.ApiException;
import com.capstone.backend.common.Codes;
import com.capstone.backend.common.DatasetInfo;
import com.capstone.backend.common.DatasetInfoReader;

/**
 * 게시대 API (명세 4.2~4.5)의 요청 해석·응답 형태·오류 코드를 DB 없이 검증한다.
 * 조회 결과의 수치(기간 합산 등)는 팀 미확정이므로 Repository·Service는 가짜(mock)로 대신한다.
 */
@WebMvcTest(BoardController.class)
class BoardControllerTest {

    static final BoardRepository.Board SAMPLE = new BoardRepository.Board(
            "1", "테스트 게시대", "ANYANG", "테스트동", "테스트 주소", 37.39, 126.96,
            "COMMERCIAL", 5, null, null, true, true);

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    BoardRepository boards;

    @MockitoBean
    BoardPopulationService population;

    @MockitoBean
    DatasetInfoReader dataset;

    @BeforeEach
    void setUp() {
        when(dataset.current()).thenReturn(DatasetInfo.UNLABELED);
    }

    // ---- GET /boards (4.2)

    @Test
    void listReturnsBoardsAndCount() throws Exception {
        when(boards.findAll(anyList(), anyList(), any(), any(), any(), any())).thenReturn(List.of(SAMPLE));

        mockMvc.perform(get("/api/v1/boards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.boards[0].boardId").value("1"))
                .andExpect(jsonPath("$.data.boards[0].city").value("ANYANG"))
                .andExpect(jsonPath("$.data.boards[0].canInquire").value(true))
                .andExpect(jsonPath("$.data.boards[0].hasPopulationData").value(true))
                .andExpect(jsonPath("$.meta.requestId").value(startsWith("req-")))
                .andExpect(jsonPath("$.meta.syntheticData").value(true));
    }

    @Test
    void listWithoutQueryPassesNoFilter() throws Exception {
        when(boards.findAll(anyList(), anyList(), any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/boards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0));

        verify(boards).findAll(List.of(), List.of(), null, null, null, null);
    }

    @Test
    void listPassesCsvFiltersAndBbox() throws Exception {
        when(boards.findAll(anyList(), anyList(), any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/boards")
                        .param("cities", "ANYANG, GUNPO")
                        .param("purpose", "COMMERCIAL,MIXED")
                        .param("west", "126.9").param("south", "37.3")
                        .param("east", "127.0").param("north", "37.5"))
                .andExpect(status().isOk());

        verify(boards).findAll(List.of("ANYANG", "GUNPO"), List.of("COMMERCIAL", "MIXED"), 126.9, 37.3, 127.0, 37.5);
    }

    @Test
    void listWithNonNumericBboxIsValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/boards").param("west", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unexpectedErrorIsInternalErrorWithoutDetails() throws Exception {
        when(boards.findAll(anyList(), anyList(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("connection refused"));

        mockMvc.perform(get("/api/v1/boards"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."))
                .andExpect(jsonPath("$.error.requestId").value(startsWith("req-")));
    }

    // ---- GET /boards/{boardId} (4.3)

    @Test
    void detailReturnsBoard() throws Exception {
        when(boards.get(1L)).thenReturn(SAMPLE);

        mockMvc.perform(get("/api/v1/boards/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.boardId").value("1"))
                .andExpect(jsonPath("$.data.name").value("테스트 게시대"))
                .andExpect(jsonPath("$.data.faceCount").value(5))
                .andExpect(jsonPath("$.data.postingFee").isEmpty());
    }

    @Test
    void detailWithNonNumericIdIsBoardNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/boards/abc"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOARD_NOT_FOUND"))
                .andExpect(jsonPath("$.error.requestId").value(startsWith("req-")));

        verifyNoInteractions(boards);
    }

    @Test
    void detailWithUnknownIdIsBoardNotFound() throws Exception {
        when(boards.get(999L)).thenThrow(boardNotFound());

        mockMvc.perform(get("/api/v1/boards/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOARD_NOT_FOUND"));
    }

    // ---- GET /boards/{boardId}/population (4.4)

    @Test
    void populationPassesParsedFilter() throws Exception {
        when(boards.get(1L)).thenReturn(SAMPLE);
        when(population.population(eq(1L), any())).thenReturn(Map.of("boardId", "1"));

        mockMvc.perform(get("/api/v1/boards/1/population")
                        .param("gender", "MALE")
                        .param("ageGroups", "AGE_30S,AGE_20S")
                        .param("periodKind", "MONTH").param("month", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.boardId").value("1"));

        ArgumentCaptor<PopulationFilter> captor = ArgumentCaptor.forClass(PopulationFilter.class);
        verify(population).population(eq(1L), captor.capture());
        PopulationFilter f = captor.getValue();
        assertThat(f.gender()).isEqualTo("MALE");
        assertThat(f.ageGroups()).containsExactly("AGE_20S", "AGE_30S");
        assertThat(f.months()).containsExactly(Codes.DATA_YEAR + "03");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "gender=ABC",
            "ageGroups=AGE_99",
            "flowType=RUN",
            "timeBands=05_06",
            "periodKind=WEEK",
            "periodKind=SEASON",
            "periodKind=MONTH&month=13",
            "dayType=HOLIDAY"})
    void populationWithInvalidFilterIsValidationError(String query) throws Exception {
        mockMvc.perform(get("/api/v1/boards/1/population?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(population);
    }

    @Test
    void populationOfUnknownBoardIsBoardNotFound() throws Exception {
        when(boards.get(999L)).thenThrow(boardNotFound());

        mockMvc.perform(get("/api/v1/boards/999/population"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOARD_NOT_FOUND"));

        verifyNoInteractions(population);
    }

    // ---- GET /boards/{boardId}/cells (4.5)

    @Test
    void cellsPassesBoardAndFilter() throws Exception {
        when(boards.get(1L)).thenReturn(SAMPLE);
        when(population.cells(eq(SAMPLE), any())).thenReturn(Map.of("boardId", "1", "cellCount", 0));

        mockMvc.perform(get("/api/v1/boards/1/cells").param("flowType", "WALK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cellCount").value(0));

        ArgumentCaptor<PopulationFilter> captor = ArgumentCaptor.forClass(PopulationFilter.class);
        verify(population).cells(eq(SAMPLE), captor.capture());
        assertThat(captor.getValue().flowType()).isEqualTo("WALK");
    }

    @Test
    void cellsWithInvalidFilterIsValidationError() throws Exception {
        when(boards.get(1L)).thenReturn(SAMPLE);

        mockMvc.perform(get("/api/v1/boards/1/cells").param("gender", "ABC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(population);
    }

    @Test
    void cellsOfUnknownBoardIsBoardNotFound() throws Exception {
        when(boards.get(999L)).thenThrow(boardNotFound());

        mockMvc.perform(get("/api/v1/boards/999/cells"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOARD_NOT_FOUND"));
    }

    private static ApiException boardNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "BOARD_NOT_FOUND", "게시대를 찾을 수 없습니다.");
    }
}
