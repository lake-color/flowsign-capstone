package com.capstone.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * 적재 스크립트가 dataset_info에 남긴 세트 종류를 백엔드가 그대로 읽는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class DatasetInfoReaderTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DatasetInfoReader reader;

    @BeforeEach
    void clear() {
        jdbc.update("TRUNCATE dataset_info");
    }

    @Test
    void noRecordIsTreatedAsSynthetic() {
        DatasetInfo info = reader.current();

        assertThat(info.version()).isNull();
        assertThat(info.synthetic()).isTrue();
        assertThat(info.populationNotice()).contains("기록되지 않아");
    }

    @Test
    void fakeSetIsSynthetic() {
        jdbc.update("INSERT INTO dataset_info (id, dataset) VALUES (1, 'fake')");

        DatasetInfo info = reader.current();

        assertThat(info.version()).startsWith("fake-");
        assertThat(info.synthetic()).isTrue();
        assertThat(info.populationNotice()).startsWith("가짜 데이터 기반 데모입니다.");
    }

    @Test
    void realSetIsNotSynthetic() {
        jdbc.update("INSERT INTO dataset_info (id, dataset) VALUES (1, 'real')");

        DatasetInfo info = reader.current();

        assertThat(info.version()).startsWith("real-");
        assertThat(info.synthetic()).isFalse();
        assertThat(info.populationNotice()).doesNotContain("가짜");
    }
}
