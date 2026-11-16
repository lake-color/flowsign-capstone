package com.capstone.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.capstone.backend.board.BoardController;
import com.capstone.backend.board.BoardRepository;
import com.capstone.backend.board.BoardStoreRepository;
import com.capstone.backend.cell.CellController;
import com.capstone.backend.common.ApiException;
import com.capstone.backend.common.PopulationValue;
import com.capstone.backend.meta.MetaController;

/**
 * 실제 PostgreSQL(Testcontainers)에 작은 데이터를 넣고 조회 API를 끝까지 실행한다.
 * Controller 단위 테스트는 DB를 mock으로 대신하므로, 여기서 실제 SQL과 집계 결과를 확인한다.
 *
 * 인구값은 선택 기간·요일 구분의 일평균이다(합계 ÷ 일수, DayCount). 준비 데이터의 월 합계는
 * 1월(31일)·2월(28일)로 나누면 딱 떨어지게 넣었다. 예: 1월 남 stay 310 → 하루 10.0
 *
 * 준비 데이터 (게시대 1의 3×3 = Cell 3개, 값은 월 합계)
 *   board_population 2025-01 주중 20대 08~10시: 남 stay 310·walk 0 (Cell 3) / 여 stay 620 (Cell 2) / 성별 UNKNOWN stay 155 (Cell 1)
 *   board_population 2025-02 주중 30대 18~20시: 남 stay 196 (Cell 3)
 *   population_fact  2025-01: C0 stay 124, C1 stay 186, C2 없음
 *   → 1월 전체(÷31): 남 10, 여 20, 미상 5, 합 35 / 2월(÷28): 7 / C0 4, C1 6
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class BoardApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

    static final Map<String, String> JANUARY = Map.of("periodKind", "MONTH", "month", "1");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BoardController boardController;

    @Autowired
    MetaController metaController;

    @Autowired
    CellController cellController;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE store, board_population, board_cell, population_fact, board, cell, grid_spec, dataset_info");

        jdbc.update("INSERT INTO cell (cell_id, latitude, longitude) VALUES ('C0', 37.40000, 126.95), ('C1', 37.40225, 126.95), ('C2', 37.39775, 126.95), ('C9', 37.50, 127.10)");

        jdbc.update("""
                INSERT INTO board (board_id, name, city, dong, address, latitude, longitude, purpose, face_count, posting_fee)
                VALUES (1, '상업 게시대', '안양시', '가동', '주소1', 37.4001, 126.9501, 'COMMERCIAL', 5, NULL),
                       (2, '행정 게시대', '군포시', '나동', '주소2', 37.3600, 126.9300, 'ADMINISTRATIVE', NULL, NULL),
                       (3, '겸용 게시대', '시흥시', '다동', '주소3', 37.3800, 126.8000, 'MIXED', 3, 0)
                """);

        jdbc.update("""
                INSERT INTO board_cell (board_id, cell_id, is_center, distance_meters)
                VALUES (1, 'C0', true, 15), (1, 'C1', false, 250), (1, 'C2', false, 250)
                """);

        jdbc.update("""
                INSERT INTO board_population (board_id, base_month, day_type, gender, age_group, time_slot, cell_count, stay, walk, drive)
                VALUES (1, '202501', 'WEEKDAY', 'MALE',    'AGE_20S', '08_10', 3, 310, 0,    NULL),
                       (1, '202501', 'WEEKDAY', 'FEMALE',  'AGE_20S', '08_10', 2, 620, NULL, NULL),
                       (1, '202501', 'WEEKDAY', 'UNKNOWN', 'AGE_20S', '08_10', 1, 155, NULL, NULL),
                       (1, '202502', 'WEEKDAY', 'MALE',    'AGE_30S', '18_20', 3, 196, NULL, NULL)
                """);

        jdbc.update("""
                INSERT INTO population_fact (cell_id, base_month, day_type, gender, age_group, time_slot, stay)
                VALUES ('C0', '202501', 'WEEKDAY', 'MALE', 'AGE_20S', '08_10', 124),
                       ('C1', '202501', 'WEEKDAY', 'MALE', 'AGE_20S', '08_10', 186)
                """);

        // 상가: 게시대 1의 3×3(C0·C1)에 3곳, 3×3 밖 Cell(C9)에 1곳
        jdbc.update("""
                INSERT INTO store (store_id, name, category, sub_category_name, latitude, longitude, base_month, cell_id)
                VALUES ('S1', '카페1', 'CAFE', '카페', 37.4001, 126.9502, '202609', 'C0'),
                       ('S2', '카페2', 'CAFE', '카페', 37.4022, 126.9501, '202609', 'C1'),
                       ('S3', NULL, 'PHARMACY', '약국', 37.4002, 126.9499, '202609', 'C0'),
                       ('S9', '먼 상가', 'BAR', '요리 주점', 37.5000, 127.1000, '202609', 'C9')
                """);
    }

    // ---- /boards (4.2, 4.3)

    @Test
    void boardsConvertCityAndFlags() {
        List<BoardRepository.Board> boards = boardList(Map.of());

        assertThat(boards).extracting(BoardRepository.Board::boardId).containsExactly("1", "2", "3");
        BoardRepository.Board commercial = boards.get(0);
        assertThat(commercial.city()).isEqualTo("ANYANG");
        assertThat(commercial.canInquire()).isTrue();
        assertThat(commercial.hasPopulationData()).isTrue();
        assertThat(commercial.faceCount()).isEqualTo(5);
        assertThat(commercial.postingFee()).isNull();

        BoardRepository.Board admin = boards.get(1);
        assertThat(admin.city()).isEqualTo("GUNPO");
        assertThat(admin.canInquire()).isFalse();
        assertThat(admin.hasPopulationData()).isFalse();

        assertThat(boards.get(2).postingFee()).isEqualTo(0); // 무료(0)와 정보 없음(null)을 구분
    }

    @Test
    void boardsFilterByCityPurposeAndBbox() {
        assertThat(boardList(Map.of("cities", "GUNPO"))).extracting(BoardRepository.Board::boardId).containsExactly("2");
        assertThat(boardList(Map.of("purpose", "MIXED"))).extracting(BoardRepository.Board::boardId).containsExactly("3");
        assertThat(boardList(Map.of("west", 126.94, "south", 37.39, "east", 126.96, "north", 37.41)))
                .extracting(BoardRepository.Board::boardId).containsExactly("1");
    }

    @Test
    void unknownBoardIsBoardNotFound() {
        assertThatThrownBy(() -> boardController.detail("999"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.code()).isEqualTo("BOARD_NOT_FOUND");
                });
        assertThatThrownBy(() -> boardController.population("999", JANUARY))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("BOARD_NOT_FOUND"));
    }

    // ---- /boards/{id}/population (4.4)

    @Test
    void genderAllIncludesUnknownAndSelectedGenderExcludesIt() {
        // 명세 2장 확정 규칙: gender=ALL은 UNKNOWN 포함, MALE/FEMALE 선택 시 제외
        assertThat(value(population(JANUARY), "population", "stay")).isEqualTo(35.0);
        assertThat(value(population(with(JANUARY, "gender", "MALE")), "population", "stay")).isEqualTo(10.0);
        assertThat(value(population(with(JANUARY, "gender", "FEMALE")), "population", "stay")).isEqualTo(20.0);
    }

    @Test
    void zeroAndMissingAreDistinguished() {
        Map<String, Object> data = population(JANUARY);

        PopulationValue walk = pv(data, "population", "walk");
        assertThat(walk.value()).isEqualTo(0.0);          // 관측된 0
        assertThat(walk.status()).isNotEqualTo("MISSING");

        PopulationValue drive = pv(data, "population", "drive");
        assertThat(drive.value()).isNull();                // 값 없음
        assertThat(drive.status()).isEqualTo("MISSING");

        assertThat(value(data, "population", "totalFlow")).isEqualTo(35.0);
    }

    @Test
    void otherMonthIsNotMixedIn() {
        Map<String, Object> february = population(Map.of("periodKind", "MONTH", "month", "2"));
        assertThat(value(february, "population", "stay")).isEqualTo(7.0);
    }

    // ---- 일평균 (팀 결정 2026-11-16)

    @Test
    void valuesAreDailyAverageWithUnitLabel() {
        PopulationValue stay = pv(population(JANUARY), "population", "stay");
        assertThat(stay.value()).isEqualTo(35.0);          // 1월 합계 1,085 ÷ 31일
        assertThat(stay.unitLabel()).isEqualTo("명/일");
    }

    @Test
    void weekdayDividesByWeekdaysOfMonth() {
        Map<String, String> januaryWeekdayMale = with(with(JANUARY, "dayType", "WEEKDAY"), "gender", "MALE");
        assertThat(value(population(januaryWeekdayMale), "population", "stay")).isEqualTo(13.5); // 310 ÷ 23일
    }

    @Test
    void yearDividesBy365Days() {
        // 연간 = 1월 1,085 + 2월 196 = 1,281 ÷ 365일
        assertThat(value(population(Map.of()), "population", "stay")).isEqualTo(3.5);
    }

    @Test
    void monthlyChartDividesEachMonthByItsOwnDays() {
        List<Map<String, Object>> monthly = list(population(with(JANUARY, "dayType", "WEEKDAY")), "monthly");

        assertThat(((PopulationValue) monthly.get(0).get("value")).value()).isEqualTo(47.2); // 1,085 ÷ 1월 주중 23일
        assertThat(((PopulationValue) monthly.get(1).get("value")).value()).isEqualTo(9.8);  // 196 ÷ 2월 주중 20일
    }

    @Test
    void hourlyAlwaysHasSevenBandsAndMarksSelection() {
        List<Map<String, Object>> hourly = list(population(with(JANUARY, "timeBands", "08_10")), "hourly");

        assertThat(hourly).hasSize(7);
        assertThat(hourly).extracting(h -> h.get("timeBand"))
                .containsExactly("06_08", "08_10", "10_12", "12_14", "14_16", "16_18", "18_20");
        assertThat(hourly).extracting(h -> h.get("selected"))
                .containsExactly(false, true, false, false, false, false, false);
        assertThat(((PopulationValue) hourly.get(1).get("value")).value()).isEqualTo(35.0);
        assertThat(((PopulationValue) hourly.get(0).get("value")).status()).isEqualTo("MISSING");
    }

    @Test
    void monthlyAlwaysHasTwelveMonthsAndMarksSelection() {
        List<Map<String, Object>> monthly = list(population(JANUARY), "monthly");

        assertThat(monthly).hasSize(12);
        assertThat(monthly).extracting(m -> m.get("selected"))
                .containsExactly(true, false, false, false, false, false, false, false, false, false, false, false);
        assertThat(((PopulationValue) monthly.get(0).get("value")).value()).isEqualTo(35.0);
        assertThat(((PopulationValue) monthly.get(1).get("value")).value()).isEqualTo(7.0);
        assertThat(((PopulationValue) monthly.get(2).get("value")).status()).isEqualTo("MISSING");
    }

    @Test
    void genderDistributionListsUnknownSeparately() {
        List<Map<String, Object>> genders = list(population(JANUARY), "genderDistribution");

        assertThat(genders).extracting(g -> g.get("key")).containsExactly("MALE", "FEMALE", "UNKNOWN");
        assertThat(genders).extracting(g -> ((PopulationValue) g.get("population")).value())
                .containsExactly(10.0, 20.0, 5.0);
    }

    @Test
    void partialWhenFewerCellsMatchThanExpected() {
        Map<String, Object> all = population(JANUARY);
        assertThat(all.get("expectedCellCount")).isEqualTo(3);
        assertThat(all.get("matchedCellCount")).isEqualTo(3);
        assertThat(all.get("aggregationStatus")).isNotEqualTo("PARTIAL");

        Map<String, Object> female = population(with(JANUARY, "gender", "FEMALE"));
        assertThat(female.get("matchedCellCount")).isEqualTo(2);
        assertThat(female.get("aggregationStatus")).isEqualTo("PARTIAL");
    }

    @Test
    void populationReportsCenterCellAndAppliedFilter() {
        Map<String, Object> data = population(with(JANUARY, "gender", "MALE"));

        assertThat(data.get("boardId")).isEqualTo("1");
        assertThat(data.get("centerCellId")).isEqualTo("C0");
        @SuppressWarnings("unchecked")
        Map<String, Object> applied = (Map<String, Object>) data.get("filterApplied");
        assertThat(applied).containsEntry("gender", "MALE");
        assertThat(data.get("dataNotice")).isNotNull();
    }

    // ---- /boards/{id}/cells (4.5)

    @Test
    void cellsReturnCenterFirstWithPerCellPopulation() {
        Map<String, Object> data = boardController.cells("1", JANUARY).data();
        List<Map<String, Object>> cells = list(data, "cells");

        assertThat(data.get("cellCount")).isEqualTo(3);
        assertThat(data.get("centerCellId")).isEqualTo("C0");
        assertThat(cells).extracting(c -> c.get("cellId")).containsExactly("C0", "C1", "C2");
        assertThat(cells).extracting(c -> c.get("isCenter")).containsExactly(true, false, false);
        assertThat(((PopulationValue) cells.get(0).get("population")).value()).isEqualTo(4.0);
        assertThat(((PopulationValue) cells.get(1).get("population")).value()).isEqualTo(6.0);
        assertThat(((PopulationValue) cells.get(2).get("population")).status()).isEqualTo("MISSING");
        assertThat(cells.get(0).get("geometry")).isNotNull();
    }

    // ---- /boards/{id}/stores (4.8)

    @Test
    void storesInThreeByThreeAreListedAndCounted() {
        Map<String, Object> data = boardController.stores("1").data();
        List<BoardStoreRepository.Store> stores = list(data, "stores");

        assertThat(data.get("baseMonth")).isEqualTo("202609");
        assertThat(data.get("totalCount")).isEqualTo(3);
        assertThat(stores).extracting(BoardStoreRepository.Store::storeId).containsExactly("S1", "S2", "S3");
        assertThat(stores.get(2).name()).isNull();
        assertThat(list(data, "categories")).containsExactly(
                new BoardStoreRepository.CategoryCount("CAFE", 2),
                new BoardStoreRepository.CategoryCount("PHARMACY", 1));
    }

    @Test
    void boardWithoutStoresReturnsEmpty() {
        Map<String, Object> data = boardController.stores("2").data();

        assertThat(data.get("totalCount")).isEqualTo(0);
        assertThat(data.get("baseMonth")).isNull();
        assertThat(list(data, "categories")).isEmpty();
        assertThatThrownBy(() -> boardController.stores("999"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("BOARD_NOT_FOUND"));
    }

    // ---- /meta (4.1)

    @Test
    void metaWithoutGridSpec() {
        Map<String, Object> data = metaController.meta().data();

        assertThat(data.get("grid")).isNull();
        assertThat(data.get("cellSizeMeters")).isEqualTo(250);
        assertThat(data.get("availableDayTypes")).isEqualTo(List.of("ALL", "WEEKDAY"));
        assertThat(data.get("dayTypeSourceStatus")).isEqualTo("VERIFIED");
    }

    @Test
    void metaReadsGridSpec() {
        jdbc.update("""
                INSERT INTO grid_spec (id, cell_size_meters, origin_latitude, origin_longitude, latitude_step_deg, longitude_step_deg, status)
                VALUES (1, 250, 37.0, 126.0, 0.002246, 0.002827, 'NOT_VERIFIED')
                """);

        MetaController.Grid grid = (MetaController.Grid) metaController.meta().data().get("grid");

        assertThat(grid).isNotNull();
        assertThat(grid.cellSizeMeters()).isEqualTo(250);
        assertThat(grid.latitudeStepDeg()).isEqualTo(0.002246);
        assertThat(grid.status()).isEqualTo("NOT_VERIFIED");
    }

    @Test
    void metaWithoutWeekdayDataIsUnconfirmed() {
        jdbc.execute("TRUNCATE board_population");

        Map<String, Object> data = metaController.meta().data();

        assertThat(data.get("availableDayTypes")).isEqualTo(List.of("ALL"));
        assertThat(data.get("dayTypeSourceStatus")).isEqualTo("UNCONFIRMED");
    }

    // ---- /cells (11장 데모 추가 API)

    @Test
    void cellsInBoundsExcludeFarCells() {
        Map<String, Object> data = cellController.cells(126.94, 37.39, 126.96, 37.41).data();

        assertThat(data.get("cellCount")).isEqualTo(3);
        assertThat(data.get("truncated")).isEqualTo(false);
        List<CellController.CellCenter> cells = list(data, "cells");
        assertThat(cells).extracting(CellController.CellCenter::cellId).containsExactlyInAnyOrder("C0", "C1", "C2");
    }

    // ---- helpers

    private List<BoardRepository.Board> boardList(Map<String, ?> q) {
        @SuppressWarnings("unchecked")
        List<BoardRepository.Board> boards = (List<BoardRepository.Board>) boardController.list(
                (String) q.get("cities"),
                (Double) q.get("west"), (Double) q.get("south"), (Double) q.get("east"), (Double) q.get("north"),
                (String) q.get("purpose")).data().get("boards");
        return boards;
    }

    private Map<String, Object> population(Map<String, String> query) {
        return boardController.population("1", query).data();
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> copy = new java.util.HashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static PopulationValue pv(Map<String, Object> data, String group, String key) {
        return ((Map<String, PopulationValue>) data.get(group)).get(key);
    }

    private static Double value(Map<String, Object> data, String group, String key) {
        return pv(data, group, key).value();
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> list(Map<String, Object> data, String key) {
        return (List<T>) data.get(key);
    }
}
