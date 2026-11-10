# FLOWSIGN REST API 상세 명세서 v0.2 — 게시대 중심 MVP

> **상태:** 개발 협업용 제안 계약(DRAFT), 2026-10-28 작성 / 2026-11-07 팀 결정 반영(v0.2.1)  
> **개발 가정:** React + TypeScript + Naver Maps → Spring Boot REST API → PostgreSQL (**PostGIS 미사용**)  
> **데이터 처리:** Python 배치에서 CSV 전처리·정규화·게시대↔Cell 매핑·사전 집계. Spring의 조회 경로에서 Python이나 원본 대용량 테이블 전체를 매 요청마다 재처리하지 않음.  
> **경계:** 기존 API v0.1은 *250m 전역 지도 인구 분포*를 포함했지만, v0.2에서는 전역 Cell 인구 표시·검색·분포 API를 제거함. **게시대를 클릭한 경우에만 그 게시대의 3×3 Cell(9칸) 인구 정보를 제공.** 지도 전체의 250m 격자선은 인구값 없이 선만 옵션으로 표시(4.1 `grid`, 6장).

## 0. 변경 이력 및 확정 범위

| 구분 | v0.2 적용 내용 | 확정 정도 |
|---|---|---|
| 대상 | 경기도 안양시·군포시·시흥시 현수막 지정게시대 | 확정 |
| 원본 데이터 | 통신사 유동인구 데이터, 12개월, **250m Cell**, 성별, 6연령대, 2시간 7구간, 6종 인구값. 표준 컬럼은 1.1 참고 | 실제 파일로 코드값 확인 필요 |
| 주중·주말 | 원본에 주중/주말 구분 포함 | 실제 값 표기 확인 필요 |
| Cell 좌표 | WGS84 경도·위도, Cell **중심점**. Cell은 중심 ±125m 정사각형(250m × 250m) | 확정(2026-11-07 250m로 정정), 실데이터로 격자 정합성 검증 |
| 게시대 | 상업용/겸용/행정용을 하나의 `board` 테이블과 `purpose` 컬럼으로 관리 | 확정 방향 |
| 영향권 | **게시대 위경도가 속한 Cell(가장 가까운 Cell 중심) 1개 + 주변 8칸 = 3×3, 최대 9 Cell(약 750m × 750m)**; `board_cell`에서 조회. 반경(`radiusMeters`) 개념 제거 | 확정 (2026-11-07, 250m Cell 기준 유지) |
| 게시대 마커 위치 | Cell 중심이 아닌 **게시대 자체 위경도** | 확정 |
| 250m 격자선 | 지도 전체에 격자선 표시, 체크박스로 켜고 끔. 인구값 없음 | 확정 |
| 통신사 Cell 레이어 | 데이터에 있는 Cell을 지도 전체에 사각형으로 표시(화면 범위만 조회), 체크박스로 켜고 끔. **인구값 없음**. 인구는 선택 게시대와 그 3×3칸에만 표시 | 확정 (2026-11-10) |
| E 분석 테이블 | `board_population`: 게시대별 3×3 Cell 집계 | 설계 완료, 적재 전 검증 필요 |
| F 전역 분포 | `grid250_population` 관련 화면/서비스 API | **MVP 제외**; DB 잔존 시 즉시 삭제 필요 없음 |
| 전체 격자 인구 | 모든 250m Cell의 인구 정보 제공 | **확장 기능**, MVP에서 구현하지 않음 |
| 추천 | 조건 입력 후 TOP 10, 10개씩 더 보기, 추천 이유와 점수 구성 | 기능 확정. **계산 위치는 Spring 요청 시 계산(확정)**, 가중치·산식은 임시값(미결) |
| 게시대 상세 차트 | 시간대별·성별 등 인구 차트 포함 | 포함 확정, 차트 구성·라이브러리 미정 |
| 지도 | Naver Maps JS 1순위, Leaflet 2순위(대안) | 확정 |
| 기술 미사용 | PostGIS, pyproj 사용 안 함. 거리 계산은 Python(numpy) 근사식 | 확정 |
| DB 적재 | Python이 표준 CSV 생성 → `psql \copy`로 적재. 테이블은 **Flyway migration만** 생성 | 확정 |
| 행정용 | 초기 지도에는 항상 표시, 선택 시 B2G 추천 가능, 문의 불가 | 확정 |
| 문의 | **실제 구매·예약 아님**, 연락처와 동의를 받아 **신청 문의만 저장** | 확정 |
| 인증·결제·예약 확정·관리 화면 | MVP 제외 | 확정 |

**이 문서의 API 경로·필드명·HTTP 상태·JSON 형태는 팀이 채택할 수 있도록 제안한 계약**이며, 아직 구현된 서버 엔드포인트라는 뜻은 아니다. 이전 v0.1 API 명세 대신 **v0.2를 구현 기준**으로 사용한다.

## 1. 데이터 파이프라인과 DB ↔ API 책임

```text
유동인구 원본 CSV (1.1)              게시대 원본 CSV (공공데이터포털 전국현수막게시대표준데이터)
  ↓ Python 전처리기: 컬럼명·코드 매핑, 타입 정제, 대용량 청크 처리
  ↓   ※ 원본 형식이 달라지면 매핑 파일만 수정한다
  ↓ Python 표준 CSV 출력
     A cell              = 250m Cell ID / 중심 위경도
     B population_fact   = Cell × 월 × day_type × 성별 × 연령 × 시간대별 6종 인구
     C board             = 게시대 기본정보(상업용/겸용/행정용, 위경도)
  ↓ Python 배치: 매핑·집계 (원본 형식과 무관)
     D board_cell        = 게시대 ↔ 소속 Cell + 주변 8칸 (최대 9행/게시대)
     E board_population  = 게시대 3×3 Cell 합계 × 월 × day_type × 성별 × 연령 × 시간대별 인구
     grid_spec           = 지도 격자선용 기준값 1행 (원점·위경도 간격·검증 상태)
  ↓ psql \copy 로 PostgreSQL 적재 (테이블은 Flyway migration이 생성)
  ↓ Spring Boot REST API (추천 점수는 여기서 요청 시 계산)
  ↓ React + Naver Maps
```

- 지도 마커: `board`만 조회. **초기 로딩에 Cell 인구 정보 없음.** 마커는 게시대 자체 위경도에 표시.
- 지도 격자선: `/meta`의 `grid` 기준값으로 프론트가 현재 화면 범위의 선만 계산해 그림. Cell 목록을 내려받지 않음.
- 게시대 상세: `board` 정적 속성과 `board_population` 집계 조회.
- 게시대 3×3 Cell: `board_cell` + `cell` + `population_fact`에서 선택 게시대의 Cell만 조회. 원본 전체를 지도에 전송하지 않음.
- 추천: `board_population`에서 조건별 T/N/Tf 집계 후 **Spring이 요청 시 점수 계산**. 모든 추천 조합을 Python에 미리 계산해 넣지 않음.

### 1.1 원본 컬럼 → 표준 컬럼

원본 컬럼명과 코드값은 코드·문서에 두지 않고 **매핑 파일**로 관리한다. 실제 원본용 `python/raw_mapping.json`은 저장소에 올리지 않고(`.gitignore`) 로컬에만 두며, 저장소에는 가짜 데이터 형식의 `python/raw_mapping.example.json`만 있다. 전처리기와 가짜 데이터 생성기는 `raw_mapping.json`이 있으면 그것을, 없으면 예시 파일을 쓴다(`--mapping`으로 직접 지정 가능). 매핑되지 않는 값이 나오면 전처리기가 오류를 낸다.

| 표준 컬럼 | 의미 | 표준 값 |
|---|---|---|
| `base_month` | 연월 | `YYYYMM` 6자리 |
| `day_type` | 주중/주말 | `WEEKDAY`/`WEEKEND` (원본에 컬럼이 없으면 `UNSPECIFIED`) |
| `city_name` | 시 이름 | 참고 속성 (`cell.city_name`) |
| `dong_name` | 동 이름 | 참고 속성 (`cell.dong_name`) |
| `cell_id` | Cell ID | 앞자리 0 등을 보존하도록 **문자열**로 저장 |
| `longitude` / `latitude` | Cell 중심 좌표 | WGS84 경도/위도 |
| `gender` | 성별 | `MALE`/`FEMALE`, NULL → `UNKNOWN` |
| `age_group` | 연령 | `UNDER_20`/`AGE_20S`/…/`AGE_60_PLUS`, NULL → `UNKNOWN` |
| `time_slot` | 시간대 | `06_08`(06:00~07:59) … `18_20` (2시간 7구간), NULL → `UNKNOWN` |
| `stay` / `walk` / `drive` | 체류 / 보행 / 주행 인구 | 실수 |
| `residence` / `work` / `visit` | 거주 / 근무 / 방문 인구 | 실수 |

매핑 파일 구조: `columns`는 `표준 컬럼명: 원본 컬럼명`, `codes`는 분류 컬럼별 `{원본 코드: 표준 코드}`. 예시는 `raw_mapping.example.json`을 본다.

- 인구 6종은 **소수점이 있는 숫자**로 본다. DB에는 실수형(`double precision`)으로 저장한다.
- 인구값의 **0과 NULL을 구분**한다. 0은 관측된 0, NULL은 값 없음이며 NULL을 0으로 채우지 않는다. 숫자가 아닌 값(마스킹 기호 등)이 나오면 전처리기가 오류를 낸다.
- 원본은 대용량 CSV로 보고, 전처리기는 청크(기본 100만 행) 단위로 읽어 메모리에 한 번에 올리지 않는다.
- 원본은 **여러 CSV 파일로 나뉘어 있을 수 있다.** 전처리기는 파일을 모두 받아 순서대로 이어 읽고 결과 하나로 합친다. 파일마다 헤더가 있어야 하고 헤더가 모두 같아야 한다(다르면 오류). 파일끼리 겹치는 행은 없다고 본다.
- 원본 수치는 추정치로 다루고, 성별 합 = 전체 같은 정확한 일치 검증을 전제로 하지 않는다(검증은 경고·리포트 수준).
- **NULL 처리 규칙(전처리기):** 어느 컬럼이든 결측이 있을 수 있다고 보고 모든 컬럼에 규칙을 둔다.
  - 분류 컬럼(주중구분·성별·연령·시간대) NULL → 행을 버리지 않고 `UNKNOWN`으로 보존
  - `cell_id`·`base_month` NULL → 둘 곳이 없으므로 행을 버리고 리포트에 행 수를 남김
  - 인구값 NULL → NULL 그대로
  - 위경도·시/동 이름 NULL → 같은 Cell의 다른 행 값을 사용, 끝까지 없으면 `cell.csv`에 빈 값으로 두고 리포트에 Cell 수를 남김(매핑에서 제외)
  - 형식이 다른 값(매핑표에 없는 코드, 숫자가 아닌 인구값, `YYYYMM`이 아닌 연월, 같은 Cell의 다른 좌표)은 오류로 중단
  - NULL로 보는 표기: 빈 칸, `NULL`, `null`, `Null`, `\N`. 다른 표기가 있으면 전처리기 상수에 추가
- 전처리기 출력: `population_fact.csv`(키 6개 + 인구 6종), `cell.csv`(Cell ID·중심 위경도·시 이름·동 이름, Cell당 1행), `preprocess_report.json`(행 수·NULL 수·`UNKNOWN` 수·음수 값 수·연월 목록·좌표 범위. **원본 행은 담지 않음**).
- `UNKNOWN` 행끼리는 키(Cell·월·주중구분·성별·연령·시간대)가 겹칠 수 있다. `population_fact`에는 키 UNIQUE 제약을 두지 않고, 조회·집계는 항상 `SUM`으로 한다.
- 개발·테스트는 가짜 데이터로 한다(`data-processing build fake`, 실제 게시대 3×3 주변에 가상 Cell과 난수 인구 생성). 실제 원본과 실제 매핑 파일은 저장소에 두지 않는다.
- **데이터 세트 분리:** 원본은 `data/raw/`에만 두고, 결과는 `data/generated_real/`(실제 통신사 원본 기반)과 `data/generated_fake/`(가짜 통신사 데이터 기반)에 각각 독립된 한 벌로 만든다. 가짜 세트의 `board_cell`은 실제 게시대 × 가상 Cell이므로 실제 매핑 결과가 아니다. 리포트에 `dataset`과 입력 파일 이름을 기록하고, 생성·적재 전에 세트가 모드와 맞는지 검증한다. 실제 모드는 원본이나 매핑 파일이 없으면 중단하며 가짜 데이터로 대신하지 않는다. DB 적재는 트랜잭션 하나로 테이블 6개를 교체한다.

### 1.2 게시대 ↔ Cell 매핑 규칙

- **소속 Cell:** 게시대 위경도에서 가장 가까운 Cell 중심을 가진 Cell. Cell 사각형의 가장자리에 찍혀도 그 Cell이 소속 Cell이며, 소속 Cell이 없는 게시대는 없는 것으로 본다(거리 제한 없음).
- **3×3:** 소속 Cell 중심에서 상하좌우(250m)와 대각선(약 354m) 이웃까지 포함한다. 다음 바깥 칸은 500m부터이므로 그 사이 거리로 구분한다. 영향권 전체는 약 750m × 750m이다.
- **데이터가 없는 Cell:** 9칸 중 데이터가 없는 칸(중심 Cell 포함)은 집계·점수 계산에서 빼고 나머지 칸으로 계산한다. `expectedCellCount`/`matchedCellCount`로 표시한다. 9칸 모두 없으면 해당 게시대는 `hasPopulationData=false`로 추천에서 제외한다.
- **같은 Cell의 여러 게시대:** 같은 9칸을 공유하므로 점수가 같다. 250m Cell이므로 같은 Cell을 공유하는 게시대가 흔할 수 있다. 4.6의 동점 정렬 규칙을 따른다.
- **격자 정합성 검증:** Python 배치가 모든 Cell 중심이 `grid_spec` 격자 위에 있는지 검증한다. 허용 오차를 넘으면 `grid_spec.status=NOT_VERIFIED`가 되고, 프론트는 격자선 옵션을 비활성화한다. 허용 오차 값은 실데이터 확인 후 정한다. 격자 검증은 **격자선 표시에만** 쓰이며, Cell 사각형 표시와 매핑·집계에는 영향이 없다(아래 지오메트리 계약).
- 공간 계산은 모두 Python 배치에서 한 번 수행하며 PostGIS를 사용하지 않는다. DB에는 위경도를 `double precision` 컬럼으로 저장한다.
- 광고 신청 문의: `inquiry`에 Spring 트랜잭션으로 저장. 조회용 관리자 API 없음.
- `grid250_population`은 더 이상 새로 만들 필요 없음. 이미 만들어졌다면 이후 삭제 여부 별도 판단.

## 2. 공통 API 규약

- **Base URL:** `/api/v1`; JSON UTF-8; 필드 `camelCase`; 인증 없는 데모 MVP.
- 지도 좌표 API는 위경도 `latitude`, `longitude` 및 GeoJSON **EPSG:4326**으로 제공. GeoJSON 좌표 순서는 `[longitude, latitude]`.
- DB 키 `board.board_id BIGINT`는 API에서 **문자열** `boardId`로 직렬화하여 JavaScript 정밀도 문제를 회피한다. `cellId`는 원본 ID 문자열.
- `city`: `ANYANG`, `GUNPO`, `SIHEUNG` (DB의 `city='안양시'` 등과 백엔드에서 변환).
- `purpose`: `COMMERCIAL`, `MIXED`, `ADMINISTRATIVE`. `canInquire=true`는 `COMMERCIAL`/`MIXED`만.
- `gender`: `ALL`, `MALE`, `FEMALE`. 원본 성별 NULL은 DB에 `UNKNOWN`으로 보존한다. **`ALL`에는 `UNKNOWN`을 포함**하고, `MALE`/`FEMALE` 선택 시에는 제외한다. 따라서 남+여 합은 전체보다 작을 수 있다. 성별 분포 차트에는 `UNKNOWN` 항목을 따로 표시할 수 있다.
- `ageGroups`: `UNDER_20`, `AGE_20S`, `AGE_30S`, `AGE_40S`, `AGE_50S`, `AGE_60_PLUS`.
- `flowType`: `ALL`, `STAY`, `WALK`, `DRIVE`.
- `timeBands`: `06_08`, `08_10`, `10_12`, `12_14`, `14_16`, `16_18`, `18_20` (06:00~19:59를 2시간 7구간으로 해석).
- `period`: `{kind:"YEAR",year:2025}` | `{kind:"SEASON",year:2025,season:"SPRING"|"SUMMER"|"AUTUMN"|"WINTER"}` | `{kind:"MONTH",year:2025,month:1..12}`.
- `dayType`: `ALL`, `WEEKDAY`, `WEEKEND`. 원본에 주중/주말 구분이 포함될 예정. 실제 파일에 주중/주말 구분이 없으면 `day_type=UNSPECIFIED`; 이 경우 서버는 실제로 지원되는 옵션만 `/meta`에 반환하며 없는 구분을 **추정 생성하지 않는다**.
- 배열 GET 쿼리는 쉼표 구분: `ageGroups=AGE_20S,AGE_30S`, `timeBands=06_08,08_10` 등. 쿼리 생략 시 서버/클라이언트가 약속한 기본값 적용.
- 기간 GET 쿼리에는 `periodKind=YEAR|SEASON|MONTH`, `season`(SEASON일 때 필수), `month`(MONTH일 때 필수)를 사용.
- 값은 **`PopulationValue`**로 내려주며 `0`과 `null`을 구분. `PARTIAL`, `MASKED`, `MISSING`, `UNVERIFIED`는 수치가 제공되지 않을 수 있음. 원본에 값이 존재하더라도 민감한 250m 소수 집계는 공개 가능 여부 검증 필요.
- 전체 인구는 체류+보행+주행 계열에서만 정의하고, 거주+근무+방문을 동일한 '중복 없는 사람 수'로 주장하지 않음. 시간대 합산 값도 중복 없는 실인원이 아니라 **집계 유동 규모**임.
- **월 값의 단위(평균/합계), 주중·주말 결합 방법(L3/L7)이 확인되지 않은 경우, 숫자를 무리하게 합산하지 않음.** 필요한 결과가 도출되지 않으면 데이터 미준비 오류를 반환하고 합성 Mock임을 숨기지 않음.
- `requestId`는 추적용 식별자; DB나 분석 로그에 문의 연락처·개인정보 출력 금지.

### 2.1 공통 응답 모델

```ts
type DataStatus = 'AVAILABLE' | 'PARTIAL' | 'MISSING' | 'MASKED' | 'UNVERIFIED';
interface PopulationValue {
  value: number | null;            // 0은 관측된 0, null은 숫자를 확정 불가
  status: DataStatus;
  unitLabel: string | null;        // 예: 검증 완료 뒤 '명/일'; 미확정이면 null
}
interface ApiSuccess<T> {
  data: T;
  meta: { requestId: string; datasetVersion: string | null; syntheticData: boolean };
}
interface ApiError {
  error: {
    code: string;
    message: string;               // 사용자용 한국어
    requestId: string;
    details?: Array<{field: string; reason: string}>;
  };
}
```

**공통 필터 `Criteria`:**

```ts
interface Criteria {
  cities: ('ANYANG'|'GUNPO'|'SIHEUNG')[]; // min 1, 기본 3개 모두
  gender: 'ALL'|'MALE'|'FEMALE';
  ageGroups: ('UNDER_20'|'AGE_20S'|'AGE_30S'|'AGE_40S'|'AGE_50S'|'AGE_60_PLUS')[];
  flowType: 'ALL'|'STAY'|'WALK'|'DRIVE';
  timeBands: ('06_08'|'08_10'|'10_12'|'12_14'|'14_16'|'16_18'|'18_20')[];
  period: {kind:'YEAR';year:2025} | {kind:'SEASON';year:2025;season:'SPRING'|'SUMMER'|'AUTUMN'|'WINTER'} | {kind:'MONTH';year:2025;month:number};
  dayType: 'ALL'|'WEEKDAY'|'WEEKEND';
  purposes: ('COMMERCIAL'|'MIXED'|'ADMINISTRATIVE')[];
}
```

기본 조건: 3개 시, 성별 전체, 연령대 전체(6개), 유동 유형 전체, 7개 시간대 모두, 연간, 주중/주말 전체(실제 제공 시), 게시대 용도 `COMMERCIAL,MIXED`. 겨울은 PRD 정의대로 2025년 **1·2·12월**이다.

## 3. 엔드포인트 요약

| 우선 | Method | URL | 요청 시점 | 원천 테이블 |
|---|---|---|---|---|
| P0 | `GET` | `/api/v1/meta` | 앱 초기화 | 설정·적재 상태 |
| P0 | `GET` | `/api/v1/boards` | 초기 진입/지역 변경/지도 이동 | C `board` |
| P0 | `GET` | `/api/v1/boards/{boardId}` | 게시대 선택 | C `board` |
| P0 | `GET` | `/api/v1/boards/{boardId}/population` | 상세 통계 열기 | E `board_population` |
| P1 | `GET` | `/api/v1/boards/{boardId}/cells` | 3×3 Cell 보기 활성화 | D+A+B `board_cell`,`cell`,`population_fact` |
| P0 | `POST` | `/api/v1/recommendations/search` | **추천 분석하기** 클릭 | E `board_population` (+ C `board`) |
| P0 | `POST` | `/api/v1/inquiries` | **광고 신청 문의** 전송 | `inquiry` |

**제거:** `POST /api/v1/map/population`, `GET /api/v1/cells/population` 등 **게시대와 무관한 전역 Cell 조회 API**. 기본 화면에서 Cell 자동 조회하지 않는다. 지도 전체 격자선은 `/meta`의 `grid` 기준값만으로 그리므로 별도 Cell API가 필요 없다. 전체 격자 인구 조회는 확장 기능으로 남기며 MVP에서 정의하지 않는다.

## 4. 엔드포인트 상세

### 4.1 `GET /api/v1/meta`

**목적:** 선택 가능한 필터, 데이터 적재 상태, 분석 가능 여부, 단위·결측 정책을 프론트에 알림.

**200 응답 예시** (합성 예시):

```json
{
  "data": {
    "serviceName": "FLOWSIGN",
    "dataYear": 2025,
    "cities": ["ANYANG", "GUNPO", "SIHEUNG"],
    "timeBands": ["06_08", "08_10", "10_12", "12_14", "14_16", "16_18", "18_20"],
    "availableDayTypes": ["ALL"],
    "dayTypeSourceStatus": "UNCONFIRMED",
    "cellSizeMeters": 250,
    "influenceArea": "CELL_3X3",
    "grid": {
      "cellSizeMeters": 250,
      "originLatitude": 37.0, "originLongitude": 126.0,
      "latitudeStepDeg": 0.002246, "longitudeStepDeg": 0.002827,
      "status": "NOT_VERIFIED"
    },
    "populationUnitLabel": null,
    "populationUnitStatus": "UNCONFIRMED",
    "combinationRuleStatus": "UNCONFIRMED",
    "algorithmStatus": "PROVISIONAL",
    "algorithmVersion": "provisional-v1",
    "recommendationAvailable": false,
    "maxRecommendationPageSize": 50,
    "mapScope": "BOARD_ONLY",
    "notice": "통신사 유동인구를 활용한 노출 가능성 추정이며 실제 광고 성과를 의미하지 않습니다."
  },
  "meta": {"requestId": "req-example", "datasetVersion": null, "syntheticData": true}
}
```

*중요:* 이 응답의 `availableDayTypes`/`recommendationAvailable`은 **예시 상태**일 뿐이며, 수령된 실제 데이터 상태에 따라 결정한다. 프론트는 사용할 수 없는 시간/주중 필터를 실제 값인 것처럼 보여주지 않는다.

**`grid` (지도 격자선 기준값):** Python 배치가 Cell 데이터에서 계산해 `grid_spec`에 저장한 값이다. `originLatitude/originLongitude`는 기준 Cell의 남서쪽 모서리, `latitudeStepDeg/longitudeStepDeg`는 250m에 해당하는 위도·경도 간격이다(예시 숫자는 가상값). 프론트는 이 값으로 현재 지도 화면 범위의 격자선만 계산해 그린다. `status=NOT_VERIFIED`이면 격자선 체크박스를 비활성화한다.

### 4.2 `GET /api/v1/boards`

**목적:** 초기 지도에 게시대의 경위도·종류·표시 이름을 비동기 제공. **Cell/인구 데이터는 응답에 포함하지 않는다.** 행정용도 항상 지도에 표시.

**선택적 쿼리:**

| 이름 | 타입 | 기본값 | 의미 |
|---|---|---|---|
| `cities` | CSV enum | 세 도시 전체 | 선택 지역. `cities=ANYANG,GUNPO` |
| `west,south,east,north` | decimal (동시 전달) | 없음 | 현재 지도 bbox, EPSG:4326 |
| `purpose` | CSV enum (선택) | 전체 | **관리/진단용**; 초기 화면은 행정용도 반드시 포함하도록 미지정 |

**200 응답 예시** (게시대명·좌표는 합성 샘플):

```json
{
  "data": {
    "boards": [
      {
        "boardId": "1", "name": "가상 게시대 A", "city": "ANYANG",
        "dong": "가상동", "latitude": 37.39, "longitude": 126.96,
        "purpose": "COMMERCIAL", "canInquire": true, "hasPopulationData": true
      },
      {
        "boardId": "2", "name": "가상 행정 게시대 B", "city": "ANYANG",
        "dong": "가상동", "latitude": 37.392, "longitude": 126.962,
        "purpose": "ADMINISTRATIVE", "canInquire": false, "hasPopulationData": false
      }
    ],
    "totalCount": 2
  },
  "meta": {"requestId": "req-example", "datasetVersion": "sample", "syntheticData": true}
}
```

- 좌표가 같은 게시대라도 **고유 `boardId`별로 별도 반환**, React에서 그룹/마커 묶음 UI 제공.
- 인구 미제공 게시대도 좌표가 있으면 지도에 표시. 좌표 없는 게시대는 데이터 품질 정책에 따라 제외/관리.
- `purpose=ADMINISTRATIVE` 게시대는 필터와 관계없이 지도에서는 회색 사각형 등으로 구분, 신청 문의 버튼은 없음.
- 445개 수준의 예상 데이터이므로 전체 로딩도 가능. 운영 시 bbox 조회 옵션을 둘 수 있음.

### 4.3 `GET /api/v1/boards/{boardId}`

**목적:** 게시대 마커 클릭 후 고정 정보 상세. `boardId` 문자열 숫자.

**200 `data` 필드:**

```json
{
  "data": {
    "boardId": "1", "name": "가상 게시대 A", "city": "ANYANG", "dong": "가상동",
    "address": "예시 주소", "latitude": 37.39, "longitude": 126.96,
    "purpose": "COMMERCIAL", "faceCount": 5, "postingFee": null,
    "dataReferenceDate": null, "canInquire": true,
    "hasPopulationData": true
  },
  "meta": {"requestId": "req-example", "datasetVersion": "sample", "syntheticData": true}
}
```

- 컬럼 미존재/결측은 `null`, 금액·게시면수를 임의 값으로 채우지 않음.
- 존재하지 않는 ID: `404 BOARD_NOT_FOUND`.
- 상세에 추천 점수를 포함하지 않음. 점수는 **조건을 적용한 추천 성공 응답** 또는 조건에 따른 인구 분석 결과에서만 제공.

### 4.4 `GET /api/v1/boards/{boardId}/population`

**목적:** 선택 게시대의 **3×3 Cell(최대 9칸) 인구 집계 및 그래프 데이터**. `board_population`(E) 기반. 한 번의 응답으로 요약, 7개 시간대, 12개월, 성별·연령·유동/서비스인구 차트 데이터를 제공.

**선택적 쿼리** (아래 표 모두 분석용; 별도 추천 API의 `purposes`는 사용하지 않음):

| 이름 | 타입 | 기본 |
|---|---|---|
| `gender` | `ALL,MALE,FEMALE` | `ALL` |
| `ageGroups` | CSV AgeGroup 목록 | 6개 전체 |
| `flowType` | `ALL,STAY,WALK,DRIVE` | `ALL` |
| `timeBands` | CSV TimeBand 목록 | 7개 전체 |
| `periodKind` | `YEAR,SEASON,MONTH` | `YEAR` |
| `season` | 계절 enum | `periodKind=SEASON`에서만 필수 |
| `month` | 1~12 정수 | `periodKind=MONTH`에서만 필수 |
| `dayType` | `ALL,WEEKDAY,WEEKEND` | `ALL` |

**200 응답 JSON 예시** (모든 인구 수치는 가상/미확정이므로 아래는 `null` 중심):

```json
{
  "data": {
    "boardId": "1",
    "centerCellId": "example-cell-05",
    "filterApplied": {
      "gender":"ALL", "ageGroups":["UNDER_20","AGE_20S","AGE_30S","AGE_40S","AGE_50S","AGE_60_PLUS"],
      "flowType":"ALL", "timeBands":["06_08","08_10","10_12","12_14","14_16","16_18","18_20"],
      "period":{"kind":"YEAR","year":2025}, "dayType":"ALL"
    },
    "population": {
      "totalFlow": {"value": null, "status":"UNVERIFIED", "unitLabel":null},
      "stay": {"value": null, "status":"UNVERIFIED", "unitLabel":null},
      "walk": {"value": null, "status":"UNVERIFIED", "unitLabel":null},
      "drive": {"value": null, "status":"UNVERIFIED", "unitLabel":null}
    },
    "hourly": [
      {"timeBand":"06_08","value":{"value":null,"status":"UNVERIFIED","unitLabel":null},"selected":true}
    ],
    "monthly": [
      {"month":1,"value":{"value":null,"status":"UNVERIFIED","unitLabel":null},"selected":true}
    ],
    "genderDistribution": [],
    "ageDistribution": [],
    "flowDistribution": [],
    "servicePopulation": [],
    "expectedCellCount": 0,
    "matchedCellCount": 0,
    "aggregationStatus": "UNVERIFIED",
    "dataNotice": "인구 집계 단위 확인 전이므로 수치를 제공하지 않습니다."
  },
  "meta": {"requestId":"req-example","datasetVersion":"sample","syntheticData":true}
}
```

**실제 운영 계약:** `hourly`는 **항상 7개 항목**, `monthly`는 **항상 12개 항목**을 반환한다. 예시에서는 길이를 줄이기 위해 일부만 표시했다. 성별/연령 분포·유동 유형·서비스인구 배열도 해당 항목별 값을 반환하며 비율은 데이터의 의미가 검증된 경우에만 계산한다. `expectedCellCount`는 D에서 게시대에 연결된 3×3 Cell 수(최대 9)이고, `matchedCellCount`는 선택 조건의 실제 데이터가 존재하는 Cell 수다. `matchedCellCount < expectedCellCount`이면 `aggregationStatus=PARTIAL`.

**차트 구성:** 상세 화면 차트(시간대별·성별 등)는 MVP에 포함한다. 시간대별 성별 분리 등 정확한 차트 목록과 그에 맞는 응답 필드는 아직 정하지 않았다(9장).

**차트 의미:** 전체 요약은 선택된 시간대·기간에 대해 계산. 시간대 차트는 *기간/성별/연령/주중·주말 조건을 고정*하고 7구간 전체를 가로축에 표시, 선택 구간만 `selected=true`. 월별 차트는 *시간대/성별/연령/주중·주말 조건을 고정*하고 12개월 전체를 표시, 선택 기간에 해당하는 달만 `selected=true`. 결측/마스킹은 `value=null` 표시. 사용자가 필터를 바꿔도 추천 점수는 이 API에서 임의 계산하지 않음.

### 4.5 `GET /api/v1/boards/{boardId}/cells`

**목적:** 선택 게시대의 **3×3 Cell(소속 Cell + 주변 8칸) 지도 표시 데이터와 Cell별 인구**. **게시대를 선택하고 3×3 Cell 보기를 켰을 때만 호출.**

**필터:** `GET /boards/{boardId}/population`과 같은 쿼리(성별/연령/유동유형/시간/기간/주중·주말).

**200 응답 예시:**

```json
{
  "data": {
    "boardId": "1", "centerCellId": "example-cell-05", "cellSizeMeters": 250,
    "center": {"latitude": 37.39, "longitude": 126.96},
    "cells": [
      {
        "cellId": "example-cell-05", "isCenter": true, "distanceMeters": 12.3,
        "center": {"latitude": 37.3901, "longitude": 126.9601},
        "geometry": {"type": "Polygon", "coordinates": [[[126.9587, 37.3890], "…", [126.9587, 37.3890]]]}, "geometryStatus": "AVAILABLE",
        "population": {"value": null, "status": "UNVERIFIED", "unitLabel": null}
      }
    ],
    "cellCount": 1,
    "dataNotice": "격자 정합성 검증 전이므로 Cell 경계를 제공하지 않습니다."
  },
  "meta": {"requestId":"req-example","datasetVersion":"sample","syntheticData":true}
}
```

- `center`는 게시대 위경도, `cells[].center`는 Cell 중심 위경도, `distanceMeters`는 게시대에서 Cell 중심까지의 거리.
- 예시는 1칸만 표시했다. 실제로는 최대 9칸이며 데이터가 없는 칸도 `population.status=MISSING`으로 포함한다.

**지오메트리 계약:** `geometry`는 **GeoJSON Polygon**. Cell 데이터에는 중심 좌표만 있으므로, 서버가 **Cell 중심 ±125m(미터 기준) 정사각형**을 변이 정남북·정동서인 근사로 계산해 반환한다(PostGIS 불필요). `grid_spec.status`와 관계없이 항상 반환하며 `geometryStatus=AVAILABLE`이다. `geometry=null`·`geometryStatus=NOT_VERIFIED`는 계약상 남겨 두지만 현재 구현은 내지 않는다. **게시대 3×3 Cell만 조회하며 전 지역 Cell 인구 API는 없음(확장 기능).**

**공개 범위:** Cell별 세부 성별·연령·인구 공개가 허용되는지는 원본 데이터 이용 조건과 소수 집계 마스킹 기준 확인 필요. 비공개 구간은 `MASKED`로 반환하거나 Cell 수치를 생략.

### 4.6 `POST /api/v1/recommendations/search`

**목적:** 사용자가 `추천 분석하기` 버튼 클릭 시 타깃 조건에 맞는 게시대 TOP N을 조회. **초기 로딩/조건 편집 중 자동 호출 금지.**

**요청 예시:**

```json
{
  "criteria": {
    "cities": ["ANYANG"],
    "gender": "FEMALE",
    "ageGroups": ["AGE_20S","AGE_30S"],
    "flowType": "WALK",
    "timeBands": ["16_18","18_20"],
    "period": {"kind":"SEASON","year":2025,"season":"SUMMER"},
    "dayType": "WEEKEND",
    "purposes": ["COMMERCIAL","MIXED"]
  },
  "page": 0,
  "pageSize": 10
}
```

**200 응답 예시:** (점수와 인구값은 *가상 설명용 숫자*, 운영에서는 검증된 데이터만 사용)

```json
{
  "data": {
    "criteria": {
      "cities":["ANYANG"],"gender":"FEMALE","ageGroups":["AGE_20S","AGE_30S"],
      "flowType":"WALK","timeBands":["16_18","18_20"],
      "period":{"kind":"SEASON","year":2025,"season":"SUMMER"},
      "dayType":"WEEKEND","purposes":["COMMERCIAL","MIXED"]
    },
    "items": [
      {
        "rank":1, "boardId":"1", "name":"가상 게시대 A",
        "city":"ANYANG", "dong":"가상동", "latitude":37.39, "longitude":126.96,
        "purpose":"COMMERCIAL", "canInquire":true,
        "score": 88.2, "displayScore":88,
        "targetPopulation":{"value":1500,"status":"AVAILABLE","unitLabel":"가상 수치"},
        "targetShare":0.31,
        "reason":"선택한 타깃 규모와 보행 비중이 높은 게시대입니다.",
        "scoreBreakdown":[
          {"key":"A","label":"타깃 규모","rawValue":1500,"percentile":0.92,"weight":0.4,"contribution":36.8},
          {"key":"B","label":"타깃 비율","rawValue":0.31,"percentile":0.85,"weight":0.3,"contribution":25.5},
          {"key":"C","label":"유형 적합도","rawValue":0.60,"percentile":0.80,"weight":0.2,"contribution":16.0},
          {"key":"D","label":"전체 규모","rawValue":4840,"percentile":0.99,"weight":0.1,"contribution":9.9}
        ],
        "sameLocationCount":1
      }
    ],
    "page":0,"pageSize":10,"totalItems":1,"hasNext":false,
    "algorithmVersion":"provisional-v1","algorithmStatus":"PROVISIONAL",
    "populationAggregationStatus":"VERIFIED"
  },
  "meta": {"requestId":"req-example","datasetVersion":"sample","syntheticData":true}
}
```

**계산안(임시값, 팀 미확정):** T/N/Tf는 게시대 3×3 Cell 합계 기준. `A=T`, `B=T/N`, `C=Tf/T`, `D=N` 각각 추천 대상 내 백분위로 변환하고 가중합(기본 가정 0.4/0.3/0.2/0.1). 유형 `ALL`이면 C 제외하고 가중치 재정규화(0.5/0.375/0.125). 최소 타깃 인구 기준·가중치·전체 규모 D 유지 여부는 실제 EDA 후 확정. **가중치와 백분위 계산을 React에서 구현하지 않는다.** 데이터 단위/조합이 검증되지 않으면 503 `ANALYSIS_NOT_READY`로 명시하고 미확정 수치를 실서비스로 반환하지 않음.

**정렬:** 반올림 전 점수 내림차순 → 게시면수 내림차순 → 부착금액 오름차순(없음 뒤) → 게시대 ID 오름차순. 같은 영향권 점수 동점 가능. 추천 10개부터 시작해 `page=1`로 추가 10개 요청. `totalItems=0`이면 **정상 `200` + `items: []`**. `ADMINISTRATIVE`는 `purposes`에 포함된 경우만 추천되며 B2G 표시·문의 불가.

**계산 위치(확정):** Spring이 E `board_population`을 읽어 **요청 시 점수 계산**한다. 원본 `population_fact` 전체를 매 요청 스캔하지 않음. 캐시를 도입한다면 알고리즘 버전/데이터셋 버전 변경 시 무효화 필수.

### 4.7 `POST /api/v1/inquiries`

**목적:** 상업용·겸용 게시대에 대한 **광고 신청 문의 접수**만 실행. **예약 확정·결제·재고 차감·지자체 신청 없음**.

**헤더:** `Idempotency-Key: <UUID v4>` 필수. React는 문의 폼 제출 시작 시 발급, 타임아웃·재시도에도 동일 키 사용. Spring은 DB UNIQUE 제약과 트랜잭션으로 중복 저장 방지.

**요청 예시:** (가짜 이메일만 사용)

```json
{
  "boardId": "1",
  "desiredPosting": {"year":2026,"month":11,"half":"FIRST"},
  "industry": "EDUCATION",
  "businessName": "시연용 가상 학원",
  "contact": "demo@example.com",
  "productionAssistanceRequired": true,
  "message": "시연용 가짜 문의입니다.",
  "privacyConsent": true,
  "privacyConsentVersion": "demo-v1"
}
```

- `boardId`: **한 게시대만** 지정(MVP). 여러 게시대 일괄 문의는 비교 기능 확장 단계.
- `desiredPosting`: 연·월 + `FIRST|SECOND`(상반월/하반월), 필수. 날짜는 데이터 분석 연도(2025)와 무관하게 미래 게시 희망 시기 지정 가능.
- `industry`: 필수. 업종 enum 목록은 미확정이므로 최종 검증 및 코드 테이블 합의 필요.
- `businessName`: 선택.
- `contact`: 필수, 전화 또는 이메일 형식. 프론트/서버 모두 검증.
- `productionAssistanceRequired`: 선택 boolean(기본 false).
- `message`: 선택, 최대 300자.
- `privacyConsent=true`, `privacyConsentVersion` 필수. 수집 항목·목적·삭제기한 동의문은 팀의 실제 운영 정책을 반영해 확정. 서버에서 동의 시간 기록.
- `ADMINISTRATIVE`는 프론트 문의 버튼 숨김 **+ 서버에서도 `403 INQUIRY_NOT_ALLOWED`**.
- 신청 저장 중 새로고침/중복 클릭/타임아웃 재요청 시 동일 키로 중복 저장 금지.

**201 성공 예시:**

```json
{
  "data": {
    "inquiryId": "1001", "status": "RECEIVED", "boardId": "1",
    "desiredPosting": {"year":2026,"month":11,"half":"FIRST"},
    "receivedAt": "2026-10-28T10:30:00+09:00",
    "notice": "문의가 접수됐습니다. 게시는 보통 2주 단위이며 지자체 절차에 따라 시기가 정해집니다."
  },
  "meta": {"requestId":"req-example","datasetVersion":"sample","syntheticData":true}
}
```

**같은 키·동일 요청 본문을 재전송하면** 기존 문의를 돌려주되 추가 INSERT 없이 `200 OK` 반환. **같은 키·다른 요청**은 `409 IDEMPOTENCY_CONFLICT`.

**권장 DB 구현:** `inquiry(id, board_id, posting_year, posting_month, posting_half, industry, business_name, contact, production_assistance_required, message, consent_version, consent_at, idempotency_key UNIQUE, payload_hash, created_at)`; `@Transactional`로 중복 검사 + INSERT(경쟁 상황은 UNIQUE 제약으로 해결); 문의 연락처 응답/로깅/분석 도구 저장 금지; 개인정보 저장 시 접속통제·삭제 정책 적용. 동의 없이는 데이터 저장 불가.

## 5. HTTP 오류 규약

| HTTP | 코드 | 의미 | UI 처리 |
|---|---|---|---|
| 400 | `VALIDATION_ERROR` | enum/빈 필수 배열/잘못된 연락처·기간 | 폼 에러 문구 |
| 400 | `CONSENT_REQUIRED` | 개인정보 동의 없음 | 체크박스 강조 |
| 400 | `UNSUPPORTED_FILTER` | 제공하지 않는 dayType·기간 | 지원 옵션 재선택 |
| 403 | `INQUIRY_NOT_ALLOWED` | 행정용 문의 등 | 문의 불가 배너 |
| 404 | `BOARD_NOT_FOUND` | 게시대 없음 | 기존 화면 유지, 재선택 유도 |
| 409 | `IDEMPOTENCY_CONFLICT` | 같은 멱등키에 다른 내용 | 재제출 세션 처리 |
| 422 | `AGGREGATION_METHOD_UNCONFIRMED` | 기간·요일 값의 수치 조합을 확정할 수 없음 | 실수치 대신 안내 |
| 429 | `TOO_MANY_REQUESTS` | 호출 빈도 초과 | 재시도 간격 적용 |
| 500 | `INTERNAL_ERROR` | 서버 오류 | 기존 성공 결과 유지 |
| 503 | `DATA_NOT_READY` | 집계 미적재/3×3 매핑 미산출 | 미준비 화면 |
| 503 | `ANALYSIS_NOT_READY` | 점수식 미확정/점수 계산 불가 | 결과 임의 생성 금지 |

예시:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "지역을 하나 이상 선택해 주세요.",
    "requestId": "req-example",
    "details": [{"field":"criteria.cities","reason":"must_not_be_empty"}]
  }
}
```

시간제한 10초(프론트 `AbortController`), 네트워크 중단은 HTTP 응답이 없을 수 있으므로 별도 네트워크 오류 상태로 처리. 이전 요청 취소는 에러 스낵바를 띄우지 않는다. 문의 POST의 타임아웃과 서버 저장 성공은 동시에 발생할 수 있으므로 **재시도에 반드시 원래 Idempotency-Key 유지**.

## 6. React 비동기 호출 규칙

1. 첫 진입: `GET /meta` + `GET /boards` 동시 또는 각각 호출. **전역 인구 지도 API는 호출하지 않는다.** 초기 지도에 게시대 마커(행정용 포함)만 게시대 위경도에 표시.
2. **250m 격자선 체크박스:** 켜면 `/meta.grid` 기준값으로 현재 화면 범위의 격자선만 계산해 그린다(서버 호출 없음). 지도 이동·확대 시 다시 계산한다. 화면에서 250m가 일정 픽셀 이상일 때만 표시하며, 기준 픽셀 값은 구현 시 화면을 보고 정한다. `grid.status=NOT_VERIFIED`이면 체크박스를 비활성화한다.
3. 마커 선택: `GET /boards/{boardId}` + `GET /boards/{boardId}/population` 병렬 호출. 로딩 중 상세 스켈레톤, 선택 마커 강조.
4. '3×3 Cell 보기' 클릭: 그때만 `GET /boards/{boardId}/cells` 호출해 9칸 경계(검증된 경우)와 Cell별 인구 표시. 해제 시 레이어만 제거, 상세 유지.
5. 추천 조건 UI에서는 **draftCriteria** 수정만 반영. `추천 분석하기` 클릭 시 `POST /recommendations/search`, 서버 성공 시 **appliedCriteria** 갱신. 요청 중 최신 요청만 결과 반영. 페이지 추가 로딩은 같은 appliedCriteria로 `page=1,2...`.
6. 추천 카드 클릭: 지도 선택 + 해당 게시대 상세. 분석 결과가 없는 게시대는 점수 표시 없이 인구 정보만 표시.
7. 행정용은 항상 마커/상세 가능, `purposes`에 선택되면 추천 결과도 가능. 문의 버튼은 항상 숨김.
8. 문의 모달은 API 성공 시에만 접수 완료. 실패 시 폼 유지하고 중복 발송 방지. **실제 예약 확인처럼 표현하지 않는다.**
9. 화면 문구에서 '실제 광고 효과', '실측 노출' 등 성과를 주장하지 않음. 데이터 출처·기간·한계·미확정 단위를 명시.
10. 로그인/회원/구매/결제/예약 확정/전체 Cell 인구 탐색(확장 기능)/전역 인구 Heatmap은 구현하지 않음. 격자선 표시는 인구값 없는 선 표시만 해당.

### 6.1 API Client 예시

```ts
// 설계 예시. 실제 타입은 OpenAPI로 생성하거나 src/types/api.ts에 정의.
const BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/v1';

export async function loadInitial(signal?: AbortSignal) {
  return Promise.all([
    fetch(`${BASE}/meta`, { signal }),
    fetch(`${BASE}/boards`, { signal }),
  ]);
}

export async function loadBoard(boardId: string, signal?: AbortSignal) {
  return Promise.all([
    fetch(`${BASE}/boards/${boardId}`, { signal }),
    fetch(`${BASE}/boards/${boardId}/population`, { signal }),
  ]);
}

export async function loadBoardCells(boardId: string, signal?: AbortSignal) {
  return fetch(`${BASE}/boards/${boardId}/cells`, { signal });
}
```

> 위 코드는 HTTP/JSON 검사와 오류 정규화를 생략한 *호출 시점 예시*. 실구현에서는 `response.ok` 확인, 10초 타임아웃, 재시도·요청 경쟁 처리 포함. HTTP 호출은 `fetch`만 사용하고 상태 관리·스키마 검증 라이브러리(zod 등)는 도입하지 않는다. 로컬 개발에서는 Vite dev server 프록시(`/api` → `localhost:8080`)로 Spring에 연결한다.

## 7. Spring Boot ↔ PostgreSQL 조회 계약

| API | 주 테이블 | 조회 권장 방식 |
|---|---|---|
| `/boards` | `board` | 단순 목록/좌표/용도 SELECT, 도시·bbox 필터 |
| `/boards/{id}` | `board` | PK 단건 SELECT |
| `/meta` | `grid_spec` 외 설정 | 격자 기준값 1행 SELECT |
| `/boards/{id}/population` | `board_population` | `board_id,base_month,day_type,gender,age_group,time_slot` 필터 및 시리즈 집계 |
| `/boards/{id}/cells` | `board_cell` + `cell` + `population_fact` | 해당 게시대 3×3 Cell ID(최대 9개) 한정 조인 |
| `/recommendations/search` | `board_population` + `board` | 조건별 집계 → T/N/Tf → Spring에서 점수 계산 → 정렬 |
| `/inquiries` | `board` + `inquiry` | `purpose` 검증 후 트랜잭션 INSERT |

- E에 집계가 저장돼 있더라도 **일부 Cell의 값이 MASKED/MISSING일 때 SUM이 '완전한 총합'처럼 보이지 않도록** `expectedCellCount`, `matchedCellCount`, 항목별 완전성 검증을 필수 적용.
- B `population_fact`는 천만 행 안팎 규모로 추정(실제 행 수는 전처리 결과로 확인): 게시대 셀 조회는 D에서 제한된 `cell_id`만 읽고 인덱스 사용, 가능하면 배치/캐시 적용.
- 좌표는 PostGIS geometry가 아닌 `latitude`, `longitude` `double precision` 컬럼(WGS84). 원본이 WGS84이므로 좌표 변환 없음.
- 테이블은 Flyway migration으로만 생성하고, Python은 표준 CSV를 만들어 `psql \copy`로 데이터만 적재한다.
- E는 `board_population`이라는 *테이블 이름*이지만 실제 생성·갱신은 Python 배치로 운영. 요청마다 `TRUNCATE`/전체 재집계 금지.
- 추천 점수는 Spring 요청 시 계산(확정). 가중치·산식은 임시값이므로 `algorithmVersion`, `algorithmStatus=PROVISIONAL`을 함께 반환한다.

## 8. 테스트/검수 기준

- [ ] 첫 화면 네트워크에서 `/meta`, `/boards`는 호출하되 `/map/population`, `/cells/population`은 없음.
- [ ] 상업용·겸용·행정용 3종 마커가 나타나고, 용도 필터는 **추천 대상**에만 적용.
- [ ] 동일 좌표 2개 게시대가 각각 다른 ID로 보존되며 그룹 선택 가능.
- [ ] `boardId` 선택 전에는 Cell 관련 API가 호출되지 않고, 3×3 Cell 토글 후에만 `/boards/{id}/cells` 호출.
- [ ] 영향권은 **250m Cell 기준 3×3**(소속 Cell + 주변 8칸, 약 750m × 750m)이며, 반경 기준이 아님.
- [ ] 게시대 마커가 Cell 중심이 아닌 게시대 위경도에 표시됨.
- [ ] 격자선 체크박스로 250m 격자선을 켜고 끌 수 있고, 켜도 서버 Cell API를 호출하지 않음.
- [ ] 샘플 게시대 몇 건의 소속 Cell이 지도상 실제 위치와 일치함.
- [ ] 시간대 7구간, 월별 12개, 주중/주말은 실제 제공 상태에 맞게 동작.
- [ ] 실제 0값과 `null`/MASKED/UNVERIFIED는 화면·추천에서 다르게 취급.
- [ ] 추천 조건 변경만으로 자동 분석하지 않음. TOP 10 + 10개씩 추가, 동일 점수일 때 정렬 재현.
- [ ] 추천 API 10초 초과/500/503 시 이전 성공 결과·지도 유지.
- [ ] 행정용 문의 버튼 없음 + POST 직접 호출해도 403.
- [ ] 문의 연락처/필수 필드/300자·동의 유효성 검사, `Idempotency-Key` 중복 제출 방지.
- [ ] 접수 완료는 **문의 접수**만 의미; 결제/예약 확정 아님.
- [ ] 공개 저장소·API에 원본 파일·원본 매핑 파일이나 문의 연락처가 노출되지 않음.

## 9. 미확정 사항 — 확정 전 임의값 금지

| ID/항목 | 현재 상태 | 대응 |
|---|---|---|
| 원본 인구 값 단위 L3 | 미확인(월평균/월합계 등) | `populationUnitLabel=null`, 결합 불가하면 422/503 |
| 원본 주중/주말 L7 | 구분 있음 확인 | 주중·주말을 합칠 때의 결합 방법은 단위(L3) 확인 후 결정 |
| 원본 코드값 | 성별·연령·시간대·주중/주말 표기 확인, 일부 값은 표기 규칙으로 추정 | 로컬 매핑 파일에 채움. 실제 파일에서 매핑 오류 없는지 확인 |
| 원본 성별 UNKNOWN L4 | **NULL → `UNKNOWN` 보존, `ALL`에 포함(확정)** | — |
| 연령 UNKNOWN | 연령 NULL → 전처리에서 `UNKNOWN` 보존(확정) | API에서 언제 포함할지 미정. 제안: `ageGroups`가 6개 전체일 때만 포함 |
| 다른 컬럼 NULL | 미확인(가능성 있음) | 전처리기 규칙(1.1)으로 처리하고 리포트로 확인 |
| 마스킹 L5 | 미확인 | 원본 표시 규칙과 공개 가능 범위를 확인하고 `MASKED` 처리 |
| 셀 좌표계 L6 | **WGS84 경위도, Cell 중심점으로 확인** | 실데이터로 격자 정합성만 검증 |
| 격자 검증 허용 오차·격자선 표시 픽셀 기준 | 방식은 확정, 값은 미정 | 실데이터·화면 확인 후 결정 |
| 추천 공식 Q7 | 가중치·산식 모두 임시값, 팀 미확정 | `algorithmStatus=PROVISIONAL`, 실데이터 분석 후 확정 |
| 게시대 원본 파일 형식 | **공공데이터포털 전국현수막게시대표준데이터 CSV(CP949), 대상 3개 시 445개, 위경도 포함 확인** | 변환 규칙은 `python/src/data_processing/board.py` |
| 게시대 가격/원본 ID 등 | 원본에 고유 ID 없음 → `board_id`는 순번. 부착금액 "무료" → 0, 빈 값 → `null`(106개) | 원본이 갱신되면 순번이 바뀔 수 있음 |
| 상세 차트 구성·라이브러리 | 차트 포함 확정, 목록(시간대별 성별 등)·라이브러리 미정 | 확정 후 4.4 응답 필드 보완 |
| `population_fact` 적재 범위 | 전체 Cell vs 게시대 3×3 Cell만. 250m 기준이면 전체도 수백만 행 수준 | 확장 기능(전체 격자 인구) 일정과 함께 결정 |
| 문의 업종 선택지·개인정보 동의 문구/보관 종료일 | 미확정 | 필드/저장 동작만 제안, 실제 동의문은 별도 합의 |

## 10. 관련 문서

- OpenAPI 초안: `FLOWSIGN_OpenAPI_v0.2_board_centered.yaml`
- A~D 기본 테이블 및 E `board_population`: 팀 설계. F `grid250_population`은 팀 결정으로 제외.
- v0.2.1(2026-11-07): 영향권을 반경 100m에서 소속 Cell 기준 3×3으로 변경, `radiusMeters` 제거, 격자선 옵션·`grid` 추가, PostGIS 미사용, 점수 Spring 계산, 원본 컬럼 매핑 반영. 같은 날 원본 Cell 크기를 50m → **250m**로 정정(3×3 유지, Cell = 중심 ±125m). 원본 값 처리 규칙(소수 인구값, 분류 컬럼 NULL → `UNKNOWN`) 반영.

## 11. 데모 구현 현황 (2026-11-07)

가짜 데이터로 동작하는 데모 초안 기준이다. 실행 방법은 README의 "빠른 시작 (데모)"를 참고한다.

**구현된 API:** `GET /meta`, `GET /boards`, `GET /boards/{boardId}`, `GET /boards/{boardId}/population`, `GET /boards/{boardId}/cells`, `GET /cells`(통신사 Cell 레이어, 아래 참고).
**미구현:** `POST /recommendations/search`, `POST /inquiries`(및 `inquiry` 테이블).

**데모에서 임시로 정한 규칙** (팀 확정 전, 바뀔 수 있음):

| 항목 | 데모 동작 |
|---|---|
| 연령·시간대 `UNKNOWN` | 해당 축을 **전체 선택했을 때만** 합계에 포함 (9장 미정 항목의 제안안) |
| 주중구분 `UNKNOWN` | `dayType=ALL`이면 포함 |
| 기간 합산 | 월 값 단위(L3)가 미확정이지만 화면 확인을 위해 기간 내 값을 **합산**해 표시. 모든 수치는 `status=UNVERIFIED`, `unitLabel=null` |
| `totalFlow` | 체류+보행+주행 합. `flowType`은 시간대·월·성별·연령 차트와 Cell별 값에 적용 |
| 분포 `ratio` | 항목 합 대비 비율을 계산해 반환 (의미 검증 전 참고값) |
| `matchedCellCount` | 조건에 맞는 `board_population` 행의 `cell_count` 최댓값 |
| 격자 검증 허용 오차 | 격자 간격의 5% (`mapping.py` `GRID_TOLERANCE`) |
| 격자선 표시 기준 | 화면에서 한 칸이 12px 이상일 때 (`frontend/src/map/common.ts` `GRID_MIN_PIXELS`) |
| 3×3 이웃 판정 | 소속 Cell 중심에서 430m 이내 Cell (`mapping.py` `NEIGHBOR_MAX_METERS`) |
| 상세 차트 | 차트 라이브러리 미정이라 CSS 막대로 표시 |

**명세와 다른 점:**

- **원본에 없는 칸:** `board_cell`에는 원본 데이터에 있는 Cell만 들어간다. 원본에 없는 칸은 Cell ID를 알 수 없으므로 `/cells` 응답에 `MISSING`으로 포함하지 않고 빠진다(4.5와 다름). `expectedCellCount`도 원본에 있는 칸 수다.
- **`/boards` 응답:** 마커 목록에도 상세와 같은 필드(주소·게시면·금액 등)를 함께 내려준다.
- **`dayTypeSourceStatus`:** 데이터에 `WEEKDAY`/`WEEKEND`가 있으면 `VERIFIED`로 반환한다.
- **`grid`:** `grid_spec` 행이 없으면 `null`을 반환한다.
- **`GET /cells` (4장에 없는 데모 추가 API):** 쿼리 `west`, `south`, `east`, `north`(필수, 지도 화면 범위). 응답 `data`는 `cells`(`cellId`, `latitude`, `longitude` — Cell 중심, 인구값 없음), `cellCount`, `truncated`, `limit`. 범위 안 Cell이 `limit`(3000, 임시값)을 넘으면 `cells=[]`, `truncated=true`를 돌려주고 프론트는 확대 안내를 띄운다. 사각형은 프론트가 Cell 중심 ±125m(미터 기준) 근사로 그리며, 격자 검증 여부와 관계없다. 잘못된 범위는 400 `VALIDATION_ERROR`.
- **게시대 데이터:** 실제 공공데이터(안양·군포·시흥 445개)를 `build` 1단계에서 표준 `board.csv`로 변환한다. `name`은 게시대위치명, `address`는 지번주소(없으면 도로명주소), `dong`은 지번주소의 동(없으면 `null`). 현수막규격·관리기관 등 `board` 테이블에 없는 컬럼은 쓰지 않는다. 유동인구는 아직 가짜 데이터이며 `build fake`가 게시대 3×3 주변 Cell만 만든다(`data/generated_fake/`).
