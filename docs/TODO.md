# TODO

FLOWSIGN 개발 작업 목록입니다. 실제로 구현을 끝낸 항목만 체크합니다.

현재는 가짜 데이터로 전처리 → 매핑 → 적재 → 조회 API → 지도 화면까지 동작하는 데모 초안 단계입니다.
데모에서 임시로 정한 규칙은 API 명세 11장을 참고하세요.
구현 현황 요약은 [README.md](../README.md)를 참고하세요.

---

## 1. API 명세 확정

- [ ] 추천 요청에 받을 조건 확정 (지역, 예산, 타겟 연령·성별, 시간대 중 무엇을 쓸지)
- [ ] 엔드포인트 목록과 URL 확정 (추천 목록, 게시대 상세, 문의 등록)
- [ ] 요청·응답 JSON 필드와 타입을 문서로 정리
- [ ] 에러 응답 형식과 HTTP 상태 코드 규칙 통일

> 이후 작업이 모두 이 명세를 기준으로 하므로 가장 먼저 확정합니다.

---

## 2. Python 데이터 전처리 및 PostgreSQL 적재

- [x] 원본 컬럼명·코드값을 매핑 파일로 분리 (`raw_mapping.json`은 로컬 전용, 공개용 예시 `raw_mapping.example.json`)
- [x] 매핑 형식 가짜 데이터 생성기 (`fake_data.py`, 기본 약 300MB)
- [x] NULL 처리 규칙과 품질 리포트(`preprocess_report.json`)
- [ ] 실제 파일로 전처리기 실행해 매핑 오류·NULL 위치 확인 (리포트만 공유)
- [ ] 연령 `UNKNOWN`을 API에서 언제 포함할지 결정
- [x] 원본 CSV 정제 → `population_fact.csv`, `cell.csv` 출력 (`preprocess.py`, 여러 파일로 나뉜 원본도 한 번에 처리)
- [x] 게시대 원본 정제 (`board` 표준 CSV, `board.py` — 공공데이터포털 CSV에서 대상 3개 시 445개)
- [x] `cell`, `population_fact` 테이블 Flyway migration 작성
  (`backend/src/main/resources/db/migration/V1__create_initial_schema.sql`)
- [x] 표준 CSV를 `psql \copy`로 적재하는 절차 작성 (`data-processing load`, 테이블 생성은 Flyway만 담당)
- [ ] 적재 결과 검증 (행 수, NULL 비율, 좌표 범위가 기대값과 맞는지 쿼리로 확인)

---

## 3. 게시대 영향권 Cell 매핑 및 인구 집계

- [x] `board` 테이블 migration 작성 (실제 게시대 원본 확인 후 컬럼 보완)
- [x] 영향권 기준 확정 → 게시대가 속한 Cell 기준 3×3(최대 9 Cell), 명세 1.2
- [x] 게시대 ↔ Cell 매핑을 `board_cell`에 적재하는 로직 작성 (`mapping.py`, Python/numpy, PostGIS 미사용)
- [x] 매핑된 Cell의 인구를 합산해 `board_population`에 저장
- [x] 격자선용 `grid_spec` 계산과 격자 정합성 검증 (허용 오차는 임시값)
- [ ] 샘플 게시대 몇 건을 지도와 대조해 매핑이 맞는지 확인

---

## 4. 추천 점수 계산 로직 구현

- [ ] 점수 산식 확정 (인구 규모, 타겟 적합도, 예산 중 가중치를 어떻게 줄지)
- [x] `python/`에 점수 계산 함수 작성 (`scoring.py`, 임시 산식·EDA용 참고 구현)
- [x] 점수 저장 방식 결정 → Spring에서 요청 시 계산
- [ ] Spring에 점수 계산 구현
- [ ] 입력 조건을 바꿔가며 상위 추천 결과가 납득 가능한지 검증

---

## 5. React 지도·필터·추천·상세·문의 UI 구현

- [x] Naver Maps SDK 연동 코드 및 API 키 환경변수 분리 (`VITE_NAVER_MAP_CLIENT_ID`)
- [ ] Naver Maps 실제 키로 동작 확인 (NCP 웹 서비스 URL 등록)
- [x] 지도 렌더링과 게시대 마커 표시 (게시대 위경도 위치, Naver / Leaflet 전환)
- [x] 250m 격자선 표시와 체크박스 켜기/끄기 (픽셀 기준은 임시값)
- [x] 선택 게시대의 3×3 Cell 표시
- [x] 통신사 Cell 전체 레이어 (`GET /cells`, 화면 범위만 조회, 인구값 없음)
- [ ] 실제 원본 Cell로 레이어가 지도와 맞는지 확인
- [x] `FilterPanel.tsx` 조건 입력 폼과 draft/적용 상태 관리 (데모: 상세 인구 조회 조건)
- [ ] 추천 조건(지역·게시대 용도) 추가와 추천 결과 목록
- [x] `BoardDetail.tsx`에 선택한 게시대 정보와 3×3 인구 표시
- [ ] 추천 결과에서 선택 시 추천 점수 표시
- [ ] 게시대 상세 인구 차트 (데모는 CSS 막대, 구성·라이브러리 미정)
- [ ] 문의 등록 화면 추가

---

## 6. Spring Boot API 및 DB 연동

- [ ] 테이블에 대응하는 JPA Entity 작성 여부 결정 (데모는 JdbcTemplate만 사용)
- [x] 조회 쿼리 작성 (데모: JdbcTemplate, `/meta`·`/boards`·`/population`·`/cells`)
- [x] 게시대 목록·상세·인구·3×3 Cell API (`/meta`, `/boards` 계열)
- [ ] 추천 목록 / 문의 등록 API 구현
- [ ] 요청 값 검증(`spring-boot-starter-validation`)과 에러 응답 처리
- [ ] 각 API에 대한 테스트 작성

---

## 7. React ↔ Spring 실제 연동

- [x] API 호출 모듈과 응답 타입 정의 (`src/api.ts`, `src/types.ts`)
- [x] 로컬 연동 경로 → Vite dev server 프록시(`/api` → `localhost:8080`) 사용, CORS 미사용
- [x] 화면의 임시 placeholder를 실제 API 데이터로 교체 (추천·문의 제외)
- [x] 로딩 및 에러 상태 처리 (데모 수준: 상세 로딩 문구, 오류 배너)

---

## 8. 통합 테스트 및 배포

- [ ] 조건 입력 → 추천 → 상세 → 문의 전체 흐름 수동 점검
- [ ] Backend 통합 테스트 (Testcontainers 기반)
- [ ] 배포 환경 결정 및 `prod` profile 환경변수 정리
- [ ] 운영 DB에 Flyway migration 적용 절차 확인

---

## 완료

- [x] 저장소 폴더 구조 정리 (`frontend` / `backend` / `python` / `database`)
- [x] PostgreSQL 17 Docker Compose 환경 구성
- [x] Spring Boot ↔ PostgreSQL 연결 및 Flyway 설정 (`spring-boot-starter-flyway`)
- [x] 상태 확인 API `GET /api/v1/health` 구현 및 테스트
- [x] React + TypeScript + Vite 초기 화면 구성
- [x] Python 3.13 + uv 환경 구성 (pandas, NumPy)

---

## 범위에서 제외

- 지도 전체 인구 표시 기능
- `grid250_population` 테이블
