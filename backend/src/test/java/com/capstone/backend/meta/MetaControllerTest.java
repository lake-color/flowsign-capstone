package com.capstone.backend.meta;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.capstone.backend.common.DatasetInfo;
import com.capstone.backend.common.DatasetInfoReader;

/**
 * GET /api/v1/meta (명세 4.1)의 고정 필드와 주중/주말 판정을 DB 없이 검증한다.
 * grid_spec 조회는 mock이 null을 돌려주므로 "grid_spec 행 없음" 상태가 된다(명세 11장).
 */
@WebMvcTest(MetaController.class)
class MetaControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JdbcTemplate jdbc;

    @MockitoBean
    DatasetInfoReader dataset;

    @BeforeEach
    void setUp() {
        when(dataset.current()).thenReturn(DatasetInfo.UNLABELED);
    }

    @Test
    void returnsSpecFields() throws Exception {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serviceName").value("FLOWSIGN"))
                .andExpect(jsonPath("$.data.dataYear").value(2025))
                .andExpect(jsonPath("$.data.cities", contains("ANYANG", "GUNPO", "SIHEUNG")))
                .andExpect(jsonPath("$.data.timeBands",
                        contains("06_08", "08_10", "10_12", "12_14", "14_16", "16_18", "18_20")))
                .andExpect(jsonPath("$.data.cellSizeMeters").value(250))
                .andExpect(jsonPath("$.data.influenceArea").value("CELL_3X3"))
                .andExpect(jsonPath("$.data.populationUnitStatus").value("UNCONFIRMED"))
                .andExpect(jsonPath("$.data.recommendationAvailable").value(false))
                .andExpect(jsonPath("$.data.mapScope").value("BOARD_ONLY"))
                .andExpect(jsonPath("$.data.notice").isNotEmpty())
                .andExpect(jsonPath("$.meta.syntheticData").value(true));
    }

    @Test
    void dayTypesFromDataAreVerified() throws Exception {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("WEEKDAY", "WEEKEND"));

        mockMvc.perform(get("/api/v1/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableDayTypes", contains("ALL", "WEEKDAY", "WEEKEND")))
                .andExpect(jsonPath("$.data.dayTypeSourceStatus").value("VERIFIED"));
    }

    @Test
    void noDayTypesInDataIsUnconfirmed() throws Exception {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableDayTypes", contains("ALL")))
                .andExpect(jsonPath("$.data.dayTypeSourceStatus").value("UNCONFIRMED"));
    }
}
