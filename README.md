# FLOWSIGN

유동인구 데이터를 기반으로 **옥외광고 게시대를 추천**하는 서비스입니다.

광고주가 지역과 타깃 조건을 입력하면, 게시대 주변 영향권(게시대가 속한 250m Cell + 주변 8칸 = 3×3)의
인구 데이터를 집계해 조건에 맞는 게시대를 추천하는 것을 목표로 합니다.

```text
유동인구 CSV ──┐
                ├→ Python 배치(전처리 · 3×3 매핑 · 집계) → 표준 CSV → psql \copy → PostgreSQL
게시대 목록  ───┘                                                                  ↓
                                         React 화면(Naver Maps / Leaflet) ← Spring Boot API
```

> 현재는 **데모 초안 단계**입니다. 가짜 데이터로 전처리 → 매핑 → 적재 → 조회 API → 지도 화면까지 동작하며,
> 추천 API·문의 API는 아직 없습니다. 바로 실행해 보려면 [빠른 시작 (데모)](#빠른-시작-데모)을 따라 하세요.
>
> - API 명세 v0.2.1: [명세서](docs/FLOWSIGN_API_SPEC_v0.2_board_centered.md) · [OpenAPI](docs/FLOWSIGN_OpenAPI_v0.2_board_centered.yaml)
> - 작업 목록: [docs/TODO.md](docs/TODO.md)

---

## 기술 스택

| 영역      | 기술                                                         |
| --------- | ------------------------------------------------------------ |
| Frontend  | React 19, TypeScript, Vite 8, Naver Maps JS v3 / Leaflet 1.9 |
| Backend   | Java 21, Spring Boot 4.1.1, Gradle Wrapper, JdbcTemplate     |
| Database  | PostgreSQL 17, Flyway (PostGIS 미사용)                        |
| Python    | Python 3.13, pandas, NumPy, uv                               |
| 개발 환경 | Docker Compose                                               |

---

## 폴더 구조

```text
flowsign/
├── frontend/                       # React + TypeScript + Vite
│   ├── .env.example                # 지도 설정 예시 (VITE_MAP_PROVIDER, VITE_NAVER_MAP_CLIENT_ID)
│   └── src/
│       ├── App.tsx                 # 화면 상태와 API 호출 흐름
│       ├── api.ts / types.ts       # fetch 기반 API 모듈, 응답 타입
│       ├── constants.ts            # 조건 코드·표시 이름
│       ├── components/             # FilterPanel(조건) / MapView(지도 툴바) / BoardDetail(상세)
│       └── map/                    # NaverMap, LeafletMap, 격자선 계산(common.ts)
├── backend/                        # Spring Boot
│   └── src/main/
│       ├── java/com/capstone/backend/
│       │   ├── health/             # GET /health
│       │   ├── common/             # 공통 응답 {data, meta}·오류 {error}, 코드값
│       │   ├── meta/               # GET /meta
│       │   ├── board/              # GET /boards, /boards/{id}, /boards/{id}/population, /boards/{id}/cells
│       │   └── cell/               # GET /cells (지도 통신사 Cell 레이어)
│       └── resources/
│           ├── application*.yml
│           └── db/migration/       # Flyway migration (테이블은 여기서만 생성)
├── python/                         # 데이터 배치 (CLI: data-processing)
│   ├── raw_mapping.example.json    # 원본 컬럼명·코드값 매핑 예시 (실제 raw_mapping.json은 Git 제외)
│   └── src/data_processing/
│       ├── preprocess.py           # 원본 → 표준 CSV (원본 형식은 매핑 파일로 읽음)
│       ├── board.py                # 게시대 원본(공공데이터포털) → 표준 board.csv
│       ├── fake_data.py            # 매핑 형식대로 가짜 인구 CSV (게시대 3×3 주변 Cell만 만들 수 있음)
│       ├── mapping.py              # 게시대 ↔ Cell 3×3 매핑, board_population, grid_spec
│       ├── load.py                 # 표준 CSV → PostgreSQL 전체 재적재
│       └── scoring.py              # 추천 점수 참고 구현 (서비스 점수는 Spring)
├── database/
│   └── docker-compose.yml          # PostgreSQL 17 실행 설정
├── data/                           # 로컬 데이터 (raw/, processed/ — Git 제외)
├── docs/                           # API 명세, OpenAPI, TODO
├── .env.example                    # DB 접속 정보 예시
├── AGENTS.md                       # AI 코딩 에이전트 작업 원칙
└── README.md
```

> 테이블 생성은 Flyway migration만 담당하고, Python은 표준 CSV를 만들어 `psql \copy`로 데이터만 적재합니다.
> `*.csv`와 실제 원본용 `python/raw_mapping.json`은 `.gitignore`로 커밋되지 않습니다. 실제 원본 데이터는 저장소에 두지 않습니다.

---

## 사전 준비

- Git
- JDK 21
- Node.js 20 이상
- Docker Desktop (PostgreSQL 컨테이너, Backend 테스트의 Testcontainers, 데이터 적재에 사용)
- uv (Python 환경 관리)

Gradle과 Python 3.13은 따로 설치하지 않습니다. Gradle은 Gradle Wrapper를, Python은 uv가 관리합니다.
로컬에 `psql`을 설치할 필요도 없습니다(적재 시 컨테이너 안의 psql 사용).

---

## 환경변수

### 저장소 루트 `.env` (DB 접속, Backend·Docker Compose 공용)

```cmd
copy .env.example .env
```

| 변수          | 설명                 | 기본값                                     |
| ------------- | -------------------- | ------------------------------------------ |
| `DB_NAME`     | 데이터베이스 이름    | `telecom`                                  |
| `DB_URL`      | JDBC 접속 URL        | `jdbc:postgresql://localhost:5432/telecom` |
| `DB_USERNAME` | DB 사용자            | `telecom`                                  |
| `DB_PASSWORD` | DB 비밀번호          | `telecom_dev`                              |
| `DB_PORT`     | 호스트에 노출할 포트 | `5432`                                     |

Spring profile 기본값은 `local`입니다. `prod`는 배포 환경이 제공하는 환경변수를 사용합니다.

### `frontend/.env` (지도, 선택)

```cmd
copy frontend\.env.example frontend\.env
```

| 변수                       | 설명                                                                     |
| -------------------------- | ------------------------------------------------------------------------ |
| `VITE_MAP_PROVIDER`        | 첫 화면 지도 `naver` / `leaflet` (기본 `leaflet`)                         |
| `VITE_NAVER_MAP_CLIENT_ID` | NAVER Cloud Platform Maps Client ID(`ncpKeyId`). 없으면 Naver 지도는 안내 문구만 표시 |

Naver 지도를 쓰려면 NCP 콘솔의 Maps 애플리케이션에 웹 서비스 URL `http://localhost:5173`을 등록해야 합니다.
Leaflet은 키 없이 OpenStreetMap 타일로 동작합니다.

---

## 빠른 시작 (데모)

실제 게시대 데이터와 가짜 유동인구 데이터로 전체 흐름을 띄웁니다. 터미널 3개를 씁니다. (Windows `cmd` 기준)

**1) DB + Backend** — Backend가 처음 뜰 때 Flyway가 테이블을 만듭니다.

```cmd
docker compose --env-file .env -f database/docker-compose.yml up -d
cd backend
gradlew.bat bootRun
```

`Started BackendApplication`이 보이면 http://localhost:8080/api/v1/health 가 `{"status":"UP"}`를 반환합니다.

**2) 데이터 변환 · 적재** — Backend를 켠 상태로 다른 터미널에서 실행합니다.

```cmd
cd python
uv sync
uv run data-processing board ../data/raw/board_raw.csv ../data/raw/board.csv
uv run data-processing fake-data ../data/raw/fake_population.csv --boards ../data/raw/board.csv
uv run data-processing preprocess ../data/raw/fake_population.csv ../data/processed
uv run data-processing map ../data/processed ../data/raw/board.csv
uv run data-processing load ../data/processed ../data/raw/board.csv
```

게시대는 실제 공공데이터, 유동인구는 가짜 데이터입니다.
`board_raw.csv`는 공공데이터포털의 「전국현수막게시대표준데이터」 CSV(CP949)를 내려받아 `data/raw/`에 둡니다.
대상 3개 시 게시대 445개와 그 3×3 주변 가짜 Cell 약 1,400개(약 290만 행, 약 300MB)가 만들어지며 전체 2분 안팎이 걸립니다.
`load`는 기존 데이터를 모두 지우고 다시 넣으므로 몇 번이든 반복해도 됩니다.

**3) Frontend**

```cmd
cd frontend
npm install
npm run dev
```

http://localhost:5173 을 엽니다.

- 상단 버튼으로 Naver / Leaflet 지도를 전환합니다.
- 게시대 마커를 누르면 오른쪽에 상세 정보와 3×3 영향권 인구 차트가 나옵니다.
- `250m 격자선`, `통신사 Cell`, `3×3 Cell 보기` 체크박스로 레이어를 켜고 끕니다.
  `통신사 Cell`은 데이터에 있는 Cell을 화면 범위만큼 사각형으로 그리며(인구값 없음), 범위가 너무 넓으면 확대 안내가 나옵니다.
- 왼쪽 조건을 바꾸고 `조건 적용`을 누르면 상세 인구가 다시 조회됩니다.
- 데모 링크: http://localhost:5173/?board=3&grid=1&cells=1&allcells=1 (게시대 선택·격자선·3×3·통신사 Cell을 켠 상태로 시작, `&map=naver`로 지도 지정)

### 문제 해결

| 증상 | 원인·해결 |
| ---- | --------- |
| 화면에 "데이터를 불러오지 못했습니다" | Backend가 꺼져 있거나 데이터 적재 전입니다. 1)·2)를 확인합니다. |
| `Port 8080 was already in use` | 이전 Backend가 남아 있습니다. 종료하거나 `gradlew.bat bootRun --args=--server.port=8081`로 띄우고, Frontend를 `set API_PROXY_TARGET=http://localhost:8081&& npm run dev`로 실행합니다. |
| `load`에서 `docker` 오류 | Docker Desktop과 `telecom-postgres` 컨테이너가 떠 있는지 확인합니다. DB 이름·사용자를 바꿨다면 `--db`, `--user`, `--container` 옵션을 줍니다. |
| `load`에서 테이블이 없다는 오류 | Backend를 한 번도 실행하지 않아 Flyway migration이 적용되지 않은 상태입니다. |
| Naver 지도에 안내 문구만 표시 | `frontend/.env`의 `VITE_NAVER_MAP_CLIENT_ID`와 NCP 웹 서비스 URL 등록을 확인하고 dev 서버를 재시작합니다. |

---

## 구성 요소별 사용법

### Python 배치 (`python/`, CLI `data-processing`)

| 명령 | 하는 일 |
| ---- | ------- |
| `board <원본.csv> <출력 board.csv> [--encoding cp949]` | 게시대 원본(공공데이터포털)에서 대상 3개 시만 골라 표준 `board.csv`로 변환 |
| `fake-data <출력.csv> [--size-mb 300 \| --cells N \| --boards board.csv] [--seed 42] [--mapping 파일]` | 매핑 파일 형식의 가짜 원본 CSV 생성 (성별·연령 NULL, 0, 소수 인구값 포함). `--boards`를 주면 그 게시대들의 3×3 Cell만 생성 |
| `preprocess <원본.csv> <출력폴더> [--encoding cp949] [--chunksize N] [--mapping 파일]` | 원본 정제 → `population_fact.csv`, `cell.csv`, `preprocess_report.json` |
| `map <전처리폴더> <board.csv>` | 3×3 매핑 → `board_cell.csv`, `board_population.csv`, `grid_spec.csv` |
| `load <전처리폴더> <board.csv> [--container --db --user]` | 6개 테이블 비우고 `\copy`로 재적재 |

- 전처리기 NULL 규칙: 분류 컬럼(주중구분·성별·연령·시간대) NULL → `UNKNOWN` 보존, `cell_id`·`base_month` NULL → 행 제외,
  인구값 NULL → 그대로. 매핑되지 않는 코드·숫자가 아닌 값은 오류로 중단합니다. 자세한 규칙은 명세 1.1을 참고하세요.
- `preprocess_report.json`에는 행 수·NULL 수 같은 집계만 들어가고 원본 행은 들어가지 않습니다.
- 원본 컬럼명·코드값은 매핑 파일로 읽습니다. `python/raw_mapping.json`이 있으면 그것을, 없으면 `raw_mapping.example.json`(가짜 데이터 형식)을 씁니다.
  실제 원본을 쓸 때는 예시 파일을 복사해 `raw_mapping.json`을 만들고 원본 컬럼명·코드값으로 채웁니다. 이 파일은 커밋하지 않습니다.
- `fake-data`와 `preprocess`는 같은 매핑 파일을 쓰므로, 로컬에 `raw_mapping.json`이 있어도 데모 명령은 그대로 동작합니다.

### Backend (`backend/`)

```cmd
gradlew.bat bootRun
gradlew.bat build
gradlew.bat test
```

- `bootRun`: 개발 서버 http://localhost:8080
- `test`: Testcontainers가 임시 PostgreSQL을 띄우므로 Docker는 필요하지만 `.env`나 로컬 DB는 필요 없습니다.

구현된 API (응답 형식은 명세 2장 `{data, meta}` / `{error}`):

| Method | URL | 설명 |
| ------ | --- | ---- |
| GET | `/api/v1/health` | 상태 확인 |
| GET | `/api/v1/meta` | 필터 선택지, 격자 기준값(`grid`), 데이터 상태 |
| GET | `/api/v1/boards` | 게시대 마커 목록 (`cities`, bbox, `purpose` 필터) |
| GET | `/api/v1/boards/{id}` | 게시대 상세 |
| GET | `/api/v1/boards/{id}/population` | 3×3 합계, 시간대·월별, 성별·연령·유동·서비스 분포 |
| GET | `/api/v1/boards/{id}/cells` | 3×3 Cell 중심·사각형(GeoJSON)·Cell별 인구 |
| GET | `/api/v1/cells?west=&south=&east=&north=` | 화면 범위 안 통신사 Cell 중심 (인구값 없음, 최대 3000개) |

`population`·`cells`는 `gender`, `ageGroups`, `flowType`, `timeBands`, `periodKind`(+`season`/`month`), `dayType` 쿼리를 받습니다.
예: `/api/v1/boards/3/population?gender=FEMALE&ageGroups=AGE_20S,AGE_30S&periodKind=SEASON&season=SUMMER`

DB 구조를 바꿀 때는 기존 migration을 고치지 않고 `V2__...sql`처럼 새 파일을 추가합니다(`ddl-auto: validate`).

### Frontend (`frontend/`)

```cmd
npm run dev
npm run build
npm run lint
```

- `dev`: http://localhost:5173, `/api` 요청은 Vite 프록시가 Backend로 전달합니다.
- HTTP 호출은 `fetch`만 쓰고, 10초 타임아웃을 둡니다. CORS 대신 Vite 프록시를 씁니다.
- 프록시 대상은 `API_PROXY_TARGET` 환경변수로 바꿀 수 있습니다(기본 `http://localhost:8080`).
- 차트 라이브러리는 아직 정하지 않아 상세 차트는 CSS 막대로 그립니다.

---

## 현재 구현 상태

### 완료

- [x] 개발 환경: PostgreSQL 17 Docker Compose, Spring Boot ↔ PostgreSQL, Flyway, React + Vite, Python + uv
- [x] 초기 스키마 migration: `cell`, `population_fact`, `board`, `board_cell`, `board_population`, `grid_spec`
- [x] Python 배치: 가짜 인구 데이터 → 전처리 → 3×3 매핑·집계 → PostgreSQL 적재
- [x] 실제 게시대 데이터(공공데이터포털, 안양·군포·시흥 445개) → 표준 `board.csv`
- [x] 조회 API: `/meta`, `/boards`, `/boards/{id}`, `/boards/{id}/population`, `/boards/{id}/cells`, `/cells`
- [x] 데모 화면: Naver Maps / Leaflet 전환, 게시대 마커, 250m 격자선, 통신사 Cell 레이어, 3×3 Cell, 조건 패널, 상세 차트

### 미구현

- [ ] 추천 API와 Spring 점수 계산 (`POST /recommendations/search`)
- [ ] 문의 API (`POST /inquiries`)와 `inquiry` 테이블
- [ ] Naver Maps 실제 키로 동작 확인
- [ ] 차트 라이브러리 결정, 새 API 테스트 코드

데모 단계에서 임시로 정한 규칙과 명세와 다른 점은 [명세 11장](docs/FLOWSIGN_API_SPEC_v0.2_board_centered.md#11-데모-구현-현황-2026-11-07)에 정리했습니다.

> 지도 전체 인구 표시 기능과 `grid250_population` 테이블은 범위에서 제외되었습니다.
