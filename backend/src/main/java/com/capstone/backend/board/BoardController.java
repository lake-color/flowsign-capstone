package com.capstone.backend.board;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.backend.common.ApiResponse;
import com.capstone.backend.common.DatasetInfo;

/**
 * 게시대 API (API 명세 4.2 ~ 4.5).
 */
@RestController
@RequestMapping("/api/v1/boards")
public class BoardController {

    private final BoardRepository boards;
    private final BoardPopulationService population;
    private final DatasetInfo dataset;

    public BoardController(BoardRepository boards, BoardPopulationService population, DatasetInfo dataset) {
        this.boards = boards;
        this.population = population;
        this.dataset = dataset;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String cities,
            @RequestParam(required = false) Double west,
            @RequestParam(required = false) Double south,
            @RequestParam(required = false) Double east,
            @RequestParam(required = false) Double north,
            @RequestParam(required = false) String purpose) {
        List<BoardRepository.Board> list = boards.findAll(
                PopulationFilter.csv(cities), PopulationFilter.csv(purpose), west, south, east, north);
        return ApiResponse.of(Map.of("boards", list, "totalCount", list.size()), dataset);
    }

    @GetMapping("/{boardId}")
    public ApiResponse<BoardRepository.Board> detail(@PathVariable String boardId) {
        return ApiResponse.of(boards.get(BoardRepository.parseId(boardId)), dataset);
    }

    @GetMapping("/{boardId}/population")
    public ApiResponse<Map<String, Object>> population(@PathVariable String boardId,
                                                       @RequestParam Map<String, String> query) {
        long id = BoardRepository.parseId(boardId);
        boards.get(id);
        return ApiResponse.of(population.population(id, PopulationFilter.from(query)), dataset);
    }

    @GetMapping("/{boardId}/cells")
    public ApiResponse<Map<String, Object>> cells(@PathVariable String boardId,
                                                  @RequestParam Map<String, String> query) {
        BoardRepository.Board board = boards.get(BoardRepository.parseId(boardId));
        return ApiResponse.of(population.cells(board, PopulationFilter.from(query)), dataset);
    }
}
