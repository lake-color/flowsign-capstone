-- FLOWSIGN 초기 스키마 (API 명세 v0.2.1 1장·7장)
-- 테이블은 이 migration만 만든다. 데이터는 Python 배치가 만든 표준 CSV를 psql copy로 적재한다.
-- PostGIS를 쓰지 않으므로 좌표는 WGS84 위경도 double precision 컬럼이다.

-- A. 250m Cell (원본 데이터에 나타난 Cell만)
CREATE TABLE cell (
    cell_id    VARCHAR(20) PRIMARY KEY,         -- 원본 Cell ID. 앞자리 0 등을 보존하도록 문자열
    latitude   DOUBLE PRECISION,                -- Cell 중심. 원본에 좌표가 없던 Cell은 NULL
    longitude  DOUBLE PRECISION,
    city_name  VARCHAR(50),                     -- 시 이름 (참고 속성)
    dong_name  VARCHAR(50)                      -- 동 이름 (참고 속성)
);

-- B. Cell × 월 × 주중구분 × 성별 × 연령 × 시간대별 인구
-- 원본 NULL 분류값은 'UNKNOWN'으로 보존되어 같은 키 조합이 여러 행일 수 있으므로 UNIQUE 제약을 두지 않는다.
-- 조회·집계는 항상 SUM으로 한다. 인구값 NULL(값 없음)과 0(관측된 0)은 구분한다.
CREATE TABLE population_fact (
    cell_id    VARCHAR(20) NOT NULL REFERENCES cell (cell_id),
    base_month CHAR(6)     NOT NULL,            -- YYYYMM
    day_type   VARCHAR(16) NOT NULL,            -- WEEKDAY / WEEKEND / UNKNOWN / UNSPECIFIED
    gender     VARCHAR(16) NOT NULL,            -- MALE / FEMALE / UNKNOWN
    age_group  VARCHAR(16) NOT NULL,            -- UNDER_20 ... AGE_60_PLUS / UNKNOWN
    time_slot  VARCHAR(8)  NOT NULL,            -- 06_08 ... 18_20 / UNKNOWN
    stay       DOUBLE PRECISION,
    walk       DOUBLE PRECISION,
    drive      DOUBLE PRECISION,
    residence  DOUBLE PRECISION,
    work       DOUBLE PRECISION,
    visit      DOUBLE PRECISION
);
CREATE INDEX idx_population_fact_cell_month ON population_fact (cell_id, base_month);

-- C. 게시대 (상업용·겸용·행정용을 purpose로 구분)
CREATE TABLE board (
    board_id            BIGINT PRIMARY KEY,
    name                VARCHAR(200)     NOT NULL,
    city                VARCHAR(20)      NOT NULL,   -- '안양시' / '군포시' / '시흥시' (API 코드는 백엔드에서 변환)
    dong                VARCHAR(50),
    address             VARCHAR(300),
    latitude            DOUBLE PRECISION NOT NULL,
    longitude           DOUBLE PRECISION NOT NULL,
    purpose             VARCHAR(20)      NOT NULL
        CHECK (purpose IN ('COMMERCIAL', 'MIXED', 'ADMINISTRATIVE')),
    face_count          INTEGER,
    posting_fee         INTEGER,                     -- 원 단위. 원본에 없으면 NULL
    data_reference_date DATE
);

-- D. 게시대 ↔ 3×3 Cell (소속 Cell + 주변 8칸 중 원본 데이터에 있는 Cell, 게시대당 최대 9행)
CREATE TABLE board_cell (
    board_id        BIGINT           NOT NULL REFERENCES board (board_id),
    cell_id         VARCHAR(20)      NOT NULL REFERENCES cell (cell_id),
    is_center       BOOLEAN          NOT NULL,
    distance_meters DOUBLE PRECISION NOT NULL,       -- 게시대 위경도 → Cell 중심
    PRIMARY KEY (board_id, cell_id)
);
CREATE UNIQUE INDEX uq_board_cell_center ON board_cell (board_id) WHERE is_center;

-- E. 게시대 3×3 Cell 합계 (Python 배치가 생성)
CREATE TABLE board_population (
    board_id   BIGINT      NOT NULL REFERENCES board (board_id),
    base_month CHAR(6)     NOT NULL,
    day_type   VARCHAR(16) NOT NULL,
    gender     VARCHAR(16) NOT NULL,
    age_group  VARCHAR(16) NOT NULL,
    time_slot  VARCHAR(8)  NOT NULL,
    cell_count INTEGER     NOT NULL,                 -- 이 행에 값을 보탠 Cell 수
    stay       DOUBLE PRECISION,
    walk       DOUBLE PRECISION,
    drive      DOUBLE PRECISION,
    residence  DOUBLE PRECISION,
    work       DOUBLE PRECISION,
    visit      DOUBLE PRECISION,
    PRIMARY KEY (board_id, base_month, day_type, gender, age_group, time_slot)
);

-- 지도 격자선 기준값 (항상 1행)
CREATE TABLE grid_spec (
    id                 SMALLINT PRIMARY KEY CHECK (id = 1),
    cell_size_meters   INTEGER          NOT NULL,
    origin_latitude    DOUBLE PRECISION NOT NULL,    -- 기준 Cell 남서쪽 모서리
    origin_longitude   DOUBLE PRECISION NOT NULL,
    latitude_step_deg  DOUBLE PRECISION NOT NULL,
    longitude_step_deg DOUBLE PRECISION NOT NULL,
    status             VARCHAR(16)      NOT NULL CHECK (status IN ('VERIFIED', 'NOT_VERIFIED'))
);
