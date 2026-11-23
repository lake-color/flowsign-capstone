-- 광고 신청 문의 (API 명세 4.7). 결제·예약이 아니라 문의 접수만 저장한다.
--
-- board_id에 외래키를 걸지 않는다: 데이터 적재(python load)와 테스트가 board를 TRUNCATE로 다시 채우는데,
-- 외래키가 있으면 TRUNCATE가 막힌다. 게시대 존재·문의 가능 여부는 서버가 저장 전에 검사하고,
-- 문의 기록은 데이터를 다시 적재해도 남는다.
--
-- contact는 개인정보다. API 응답·로그에 남기지 않는다(명세 4.7).
-- 길이 제한: message 300자는 명세, 나머지는 서버 검사와 맞춘 저장 상한이다.
CREATE TABLE inquiry (
    id                             BIGSERIAL    PRIMARY KEY,
    board_id                       BIGINT       NOT NULL,
    posting_year                   INTEGER      NOT NULL,
    posting_month                  INTEGER      NOT NULL CHECK (posting_month BETWEEN 1 AND 12),
    posting_half                   VARCHAR(6)   NOT NULL CHECK (posting_half IN ('FIRST', 'SECOND')),
    industry                       VARCHAR(50)  NOT NULL,
    business_name                  VARCHAR(100),
    contact                        VARCHAR(100) NOT NULL,
    production_assistance_required BOOLEAN      NOT NULL DEFAULT FALSE,
    message                        VARCHAR(300),
    consent_version                VARCHAR(50)  NOT NULL,
    consent_at                     TIMESTAMPTZ  NOT NULL,
    idempotency_key                UUID         NOT NULL UNIQUE,  -- 같은 키 중복 저장은 이 제약으로 막는다
    payload_hash                   CHAR(64)     NOT NULL,         -- 같은 키·다른 내용(409) 판별용 SHA-256
    created_at                     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
