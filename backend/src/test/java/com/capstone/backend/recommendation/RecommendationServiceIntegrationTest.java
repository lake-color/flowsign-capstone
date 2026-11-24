package com.capstone.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.capstone.backend.common.ApiException;
import com.capstone.backend.common.PopulationValue;

/**
 * 실제 PostgreSQL(Testcontainers)에서 추천 API의 페이지·목록 인구·오류 기준을 확인한다.
 * 점수 계산 자체는 RecommendationScorerIntegrationTest가 같은 데이터로 검증한다.
 *
 * 조건: 안양 · 상업용/겸용 · 여성 20대 · 보행 · 2025년 1월 · 주중(23일)
 *
 * population_fact는 RecommendationScorerIntegrationTest와 같고, board_population은 그 3×3 단순 합계다.
 *   게시대 1: 여 20대 walk 230(C0) + 46(C1) = 276 → 목록 인구 276/23 = 12
 *   게시대 2: 여 20대 walk 23(C2)                 → 목록 인구 23/23 = 1
 * 점수용 가중 합(게시대 1의 T 8.4)과 목록 인구(12)가 다르다는 것이 이 테스트의 요점이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RecommendationServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RecommendationService service;

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
                       (3, '행정용', '안양시', '가상동', '주소', 37.40, 126.96, 'ADMINISTRATIVE', 2, NULL)
                """);
        jdbc.execute("""
                INSERT INTO board_cell VALUES
                  (1, 'C0', true, 10), (1, 'C1', false, 250), (2, 'C2', true, 10), (2, 'C3', false, 300),
                  (3, 'C2', true, 10)
                """);
        jdbc.execute("""
                INSERT INTO population_fact (cell_id, base_month, day_type, gender, age_group, time_slot, stay, walk, drive)
                VALUES ('C0', '202501', 'WEEKDAY', 'FEMALE',  'AGE_20S', '08_10', 0,   230,  0),
                       ('C0', '202501', 'WEEKDAY', 'MALE',    'AGE_20S', '08_10', 230, 0,    0),
                       ('C0', '202501', 'WEEKDAY', 'UNKNOWN', 'AGE_20S', '08_10', 23,  NULL, NULL),
                       ('C1', '202501', 'WEEKDAY', 'FEMALE',  'AGE_20S', '08_10', 0,   46,   0),
                       ('C1', '202501', 'WEEKDAY', 'MALE',    'AGE_30S', '08_10', 0,   0,    184),
                       ('C2', '202501', 'WEEKDAY', 'FEMALE',  'AGE_20S', '08_10', 23,  23,   0),
                       ('C2', '202501', 'WEEKDAY', 'MALE',    'AGE_20S', '08_10', 414, 0,    0)
                """);
        jdbc.execute("""
                INSERT INTO board_population (board_id, base_month, day_type, gender, age_group, time_slot, cell_count,
                                              stay, walk, drive)
                VALUES (1, '202501', 'WEEKDAY', 'FEMALE',  'AGE_20S', '08_10', 2, 0,   276,  0),
                       (1, '202501', 'WEEKDAY', 'MALE',    'AGE_20S', '08_10', 1, 230, 0,    0),
                       (1, '202501', 'WEEKDAY', 'UNKNOWN', 'AGE_20S', '08_10', 1, 23,  NULL, NULL),
                       (1, '202501', 'WEEKDAY', 'MALE',    'AGE_30S', '08_10', 1, 0,   0,    184),
                       (2, '202501', 'WEEKDAY', 'FEMALE',  'AGE_20S', '08_10', 1, 23,  23,   0),
                       (2, '202501', 'WEEKDAY', 'MALE',    'AGE_20S', '08_10', 1, 414, 0,    0)
                """);
    }

    private static RecommendationRequest.Criteria januaryWeekdayWomen20sWalking(String dayType) {
        return new RecommendationRequest.Criteria(List.of("ANYANG"), "FEMALE", List.of("AGE_20S"), "WALK",
                List.of("06_08", "08_10", "10_12", "12_14", "14_16", "16_18", "18_20"),
                new RecommendationRequest.Period("MONTH", 2025, null, 1), dayType, List.of("COMMERCIAL", "MIXED"));
    }

    private static RecommendationRequest request(Integer page, Integer pageSize) {
        return new RecommendationRequest(januaryWeekdayWomen20sWalking("WEEKDAY"), page, pageSize);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }

    @Test
    void returnsRankedItemsWithBoardInfoAndDetailScreenPopulation() {
        Map<String, Object> data = service.search(request(null, null));

        assertThat(data.get("totalItems")).isEqualTo(2);
        assertThat(data.get("hasNext")).isEqualTo(false);
        assertThat(data.get("pageSize")).isEqualTo(10);
        assertThat(data.get("algorithmVersion")).isEqualTo("provisional-v2");
        assertThat(data.get("notice")).isEqualTo(RecommendationService.NOTICE);

        List<Map<String, Object>> items = items(data);
        assertThat(items).extracting(i -> i.get("boardId")).containsExactly("1", "2");
        assertThat(items).extracting(i -> i.get("rank")).containsExactly(1, 2);

        Map<String, Object> first = items.getFirst();
        assertThat(first.get("name")).isEqualTo("게시대 1");
        assertThat(first.get("city")).isEqualTo("ANYANG");
        assertThat(first.get("canInquire")).isEqualTo(true);
        // 점수용 가중 T(8.4)가 아니라 상세 화면과 같은 3×3 합계의 일평균
        assertThat(((PopulationValue) first.get("targetPopulation")).value()).isEqualTo(12.0);
        assertThat(((PopulationValue) items.get(1).get("targetPopulation")).value()).isEqualTo(1.0);
        assertThat((String) first.get("reason")).isNotBlank();
        assertThat((List<?>) first.get("scoreBreakdown")).hasSize(3);
    }

    @SuppressWarnings("unchecked")
    private static List<Double> weights(Map<String, Object> item) {
        return ((List<Map<String, Object>>) item.get("scoreBreakdown")).stream()
                .map(m -> (Double) m.get("weight")).toList();
    }

    @Test
    void sizeWeightSplitsSizeAndShareWeights() {
        RecommendationRequest.Criteria c = januaryWeekdayWomen20sWalking("WEEKDAY");

        assertThat(weights(items(service.search(new RecommendationRequest(c, null, null))).getFirst()))
                .containsExactly(40.0, 40.0, 20.0);
        assertThat(weights(items(service.search(new RecommendationRequest(c, null, null, 0.65))).getFirst()))
                .containsExactly(52.0, 28.0, 20.0);
    }

    @Test
    void pagesThroughRankedResults() {
        Map<String, Object> firstPage = service.search(request(0, 1));
        assertThat(items(firstPage)).extracting(i -> i.get("boardId")).containsExactly("1");
        assertThat(firstPage.get("hasNext")).isEqualTo(true);

        Map<String, Object> secondPage = service.search(request(1, 1));
        assertThat(items(secondPage)).extracting(i -> i.get("rank")).containsExactly(2);
        assertThat(secondPage.get("hasNext")).isEqualTo(false);

        // 결과 범위를 넘는 페이지는 오류가 아니라 빈 목록
        Map<String, Object> beyond = service.search(request(5, 10));
        assertThat(items(beyond)).isEmpty();
        assertThat(beyond.get("totalItems")).isEqualTo(2);
    }

    @Test
    void emptyPopulationIsDataNotReady() {
        jdbc.execute("TRUNCATE board_population, population_fact");

        assertThatThrownBy(() -> service.search(request(null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DATA_NOT_READY"));
    }

    @Test
    void dayTypeMissingFromDataIsUnsupported() {
        RecommendationRequest weekend = new RecommendationRequest(januaryWeekdayWomen20sWalking("WEEKEND"), null, null);

        assertThatThrownBy(() -> service.search(weekend))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("UNSUPPORTED_FILTER"));
    }
}
