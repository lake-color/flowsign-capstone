-- 게시대 3×3 Cell 안의 상가 (소상공인시장진흥공단 상가(상권)정보, 공공데이터)
-- Python 배치가 소속 Cell을 정하고 어느 게시대든 그 3×3 Cell에 든 상가만 적재한다.
-- 게시대별 업종 개수는 별도 집계 테이블 없이 조회할 때 board_cell과 이어 센다.
CREATE TABLE store (
    store_id          VARCHAR(30)      PRIMARY KEY,   -- 원본 상가업소번호
    name              VARCHAR(200),                   -- 상호명. 원본에 없으면 NULL
    category          VARCHAR(20)      NOT NULL       -- 화면용 업종 묶음 (python/src/data_processing/store.py)
        CHECK (category IN ('RESTAURANT', 'CAFE', 'BAR', 'RETAIL', 'PHARMACY', 'LIVING_SERVICE', 'EDUCATION',
                            'MEDICAL', 'VETERINARY', 'STUDY_ROOM', 'SCIENCE_TECH', 'ARTS_SPORTS',
                            'REAL_ESTATE', 'FACILITY_RENTAL', 'LODGING')),
    sub_category_name VARCHAR(100)     NOT NULL,      -- 원본 업종 소분류명 (표시용)
    latitude          DOUBLE PRECISION NOT NULL,
    longitude         DOUBLE PRECISION NOT NULL,
    base_month        CHAR(6)          NOT NULL,      -- 원본 기준월 YYYYMM
    cell_id           VARCHAR(20)      NOT NULL REFERENCES cell (cell_id)
);
CREATE INDEX idx_store_cell ON store (cell_id);
