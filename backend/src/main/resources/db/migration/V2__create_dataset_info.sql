-- 지금 DB에 적재된 데이터 세트의 종류 (항상 0행 또는 1행)
-- Python 적재(load)가 다른 테이블과 같은 트랜잭션 안에서 기록한다.
-- 백엔드는 이 값을 읽어 응답 meta.syntheticData와 안내 문구를 정한다. 행이 없으면 "기록 없음"이다.
CREATE TABLE dataset_info (
    id        SMALLINT PRIMARY KEY CHECK (id = 1),
    dataset   VARCHAR(8)  NOT NULL CHECK (dataset IN ('real', 'fake')),
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
