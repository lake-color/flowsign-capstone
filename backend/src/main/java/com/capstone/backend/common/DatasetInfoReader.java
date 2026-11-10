package com.capstone.backend.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 지금 DB에 적재된 데이터 세트가 실제인지 가짜인지 읽는다.
 *
 * Python 적재(load)가 dataset_info 테이블에 기록한 값을 요청마다 조회하므로,
 * 세트를 다시 적재해도 백엔드 설정 변경이나 재시작이 필요 없다.
 */
@Component
public class DatasetInfoReader {

    private final JdbcTemplate jdbc;

    public DatasetInfoReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DatasetInfo current() {
        return jdbc.query(
                "SELECT dataset, to_char(loaded_at, 'YYYYMMDD') AS loaded_on FROM dataset_info WHERE id = 1",
                rs -> rs.next()
                        ? new DatasetInfo(rs.getString("dataset") + "-" + rs.getString("loaded_on"),
                                !"real".equals(rs.getString("dataset")))
                        : DatasetInfo.UNLABELED);
    }
}
