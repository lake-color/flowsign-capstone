package com.capstone.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.capstone.backend.board.PopulationFilter;

/**
 * 실제 PostgreSQL(Testcontainers)에서 추천 점수 입력 SQL을 끝까지 실행한다.
 * 단위 테스트(RecommendationScorerTest)는 SQL 문자열만 보므로, 여기서 조건 적용과 가중 일평균 결과를 확인한다.
 *
 * 조건: 안양 · 상업용/겸용 · 여성 20대 · 보행 · 2025년 1월 · 주중(23일)
 *
 * 준비 데이터 (population_fact, 08~10시)
 *   C0 (게시대 1 중심): 여 20대 walk 230 / 남 20대 stay 230 / 성별 UNKNOWN stay 23 / 주말 여 walk 9999(제외)
 *   C1 (게시대 1 주변, 250m): 여 20대 walk 46 / 남 30대 drive 184
 *   C2 (게시대 2 중심): 여 20대 stay 23·walk 23 / 남 20대 stay 414 / 2월 여 walk 9999(제외)
 *   C3 (게시대 2 주변): 데이터 없음 → 계산에서 빠짐
 *   게시대 3(행정용)·4(군포)는 조건 밖이라 후보가 아니다.
 *
 * 기대값 (게시대 1: 중심 0.8 + 주변 1칸 0.2, 게시대 2: 중심 1칸만 → 1.0, 모두 ÷ 23일)
 *   게시대 1: N (0.8×483 + 0.2×230)/23 = 18.8, T (0.8×230 + 0.2×46)/23 = 8.4, Tf = 8.4
 *   게시대 2: N 460/23 = 20, T 46/23 = 2, Tf 23/23 = 1
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RecommendationScorerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RecommendationScorer scorer;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE store, board_population, board_cell, population_fact, board, cell, grid_spec, dataset_info");
        jdbc.execute("""
                INSERT INTO cell VALUES
                  ('C0', 37.3900, 126.95, '안양시', '가상동'), ('C1', 37.3922, 126.95, '안양시', '가상동'),
                  ('C2', 37.4000, 126.96, '안양시', '가상동'), ('C3', 37.4027, 126.96, '안양시', '가상동')
                """);
        jdbc.execute("""
                INSERT INTO board (board_id, name, city, dong, address, latitude, longitude, purpose, face_count, posting_fee)
                VALUES (1, '게시대 1', '안양시', '가상동', '주소', 37.39, 126.95, 'COMMERCIAL', 4, 10000),
                       (2, '게시대 2', '안양시', '가상동', '주소', 37.40, 126.96, 'MIXED', 6, NULL),
                       (3, '행정용', '안양시', '가상동', '주소', 37.40, 126.96, 'ADMINISTRATIVE', 2, NULL),
                       (4, '군포 게시대', '군포시', '가상동', '주소', 37.40, 126.96, 'COMMERCIAL', 2, NULL)
                """);
        jdbc.execute("""
                INSERT INTO board_cell VALUES
                  (1, 'C0', true, 10), (1, 'C1', false, 250), (2, 'C2', true, 10), (2, 'C3', false, 300),
                  (3, 'C2', true, 10), (4, 'C2', true, 10)
                """);
        jdbc.execute("""
                INSERT INTO population_fact (cell_id, base_month, day_type, gender, age_group, time_slot, stay, walk, drive)
                VALUES ('C0', '202501', 'WEEKDAY', 'FEMALE',  'AGE_20S', '08_10', 0,   230,  0),
                       ('C0', '202501', 'WEEKDAY', 'MALE',    'AGE_20S', '08_10', 230, 0,    0),
                       ('C0', '202501', 'WEEKDAY', 'UNKNOWN', 'AGE_20S', '08_10', 23,  NULL, NULL),
                       ('C0', '202501', 'WEEKEND', 'FEMALE',  'AGE_20S', '08_10', 0,   9999, 0),
                       ('C1', '202501', 'WEEKDAY', 'FEMALE',  'AGE_20S', '08_10', 0,   46,   0),
                       ('C1', '202501', 'WEEKDAY', 'MALE',    'AGE_30S', '08_10', 0,   0,    184),
                       ('C2', '202501', 'WEEKDAY', 'FEMALE',  'AGE_20S', '08_10', 23,  23,   0),
                       ('C2', '202501', 'WEEKDAY', 'MALE',    'AGE_20S', '08_10', 414, 0,    0),
                       ('C2', '202502', 'WEEKDAY', 'FEMALE',  'AGE_20S', '08_10', 0,   9999, 0)
                """);
    }

    private static PopulationFilter januaryWeekdayWomen20sWalking() {
        return PopulationFilter.from(Map.of("gender", "FEMALE", "ageGroups", "AGE_20S", "flowType", "WALK",
                "periodKind", "MONTH", "month", "1", "dayType", "WEEKDAY"));
    }

    @Test
    void scoresOnlyCandidatesWithWeightedDailyAverages() {
        List<ScoreFormula.Result> results = scorer.score(List.of("ANYANG"), List.of("COMMERCIAL", "MIXED"),
                januaryWeekdayWomen20sWalking());

        // 행정용(3)·군포(4)는 후보가 아니고, 타깃이 4배 많은 게시대 1이 1위
        assertThat(results.stream().map(r -> r.input().boardId()).toList()).containsExactly(1L, 2L);

        ScoreFormula.Input first = results.get(0).input();
        assertThat(first.n()).isCloseTo(18.8, within(1e-9));
        assertThat(first.t()).isCloseTo(8.4, within(1e-9));
        assertThat(first.tf()).isCloseTo(8.4, within(1e-9));
        assertThat(first.matchedCellCount()).isEqualTo(2);

        ScoreFormula.Input second = results.get(1).input();
        assertThat(second.n()).isCloseTo(20, within(1e-9));
        assertThat(second.t()).isCloseTo(2, within(1e-9));
        assertThat(second.tf()).isCloseTo(1, within(1e-9));
        // C3은 데이터가 없어 빠진다
        assertThat(second.expectedCellCount()).isEqualTo(2);
        assertThat(second.matchedCellCount()).isEqualTo(1);
    }

    @Test
    void administrativeBoardsAreScoredWhenPurposeIsSelected() {
        List<ScoreFormula.Result> results = scorer.score(List.of("ANYANG"), List.of("ADMINISTRATIVE"),
                januaryWeekdayWomen20sWalking());

        assertThat(results.stream().map(r -> r.input().boardId()).toList()).containsExactly(3L);
    }

    @Test
    void conditionWithoutDataGivesEmptyResult() {
        // 데이터에 없는 달 → 모든 칸이 빠져 추천 결과 없음 (오류가 아니라 빈 목록)
        PopulationFilter march = PopulationFilter.from(Map.of("periodKind", "MONTH", "month", "3"));

        assertThat(scorer.score(List.of("ANYANG", "GUNPO"), List.of("COMMERCIAL", "MIXED"), march)).isEmpty();
    }
}
