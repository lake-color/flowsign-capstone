package com.capstone.backend.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.capstone.backend.board.BoardStoreRepository.CategoryCount;
import com.capstone.backend.board.BoardStoreRepository.Store;

/**
 * 업종별 개수 정렬 규칙 (명세 4.8): 많은 순, 같으면 코드 순, 0건 업종은 없음.
 */
class BoardStoreRepositoryTest {

    private static Store store(String id, String category) {
        return new Store(id, null, category, "-", 37.4, 126.9);
    }

    @Test
    void countsAreSortedByCountThenCategory() {
        List<CategoryCount> counts = BoardStoreRepository.countByCategory(List.of(
                store("1", "RETAIL"), store("2", "CAFE"), store("3", "CAFE"), store("4", "BAR")));

        assertThat(counts).containsExactly(
                new CategoryCount("CAFE", 2), new CategoryCount("BAR", 1), new CategoryCount("RETAIL", 1));
    }

    @Test
    void noStoresMeansNoCounts() {
        assertThat(BoardStoreRepository.countByCategory(List.of())).isEmpty();
    }
}
