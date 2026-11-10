# FLOWSIGN

유동인구 데이터를 기반으로 **옥외광고 게시대를 추천**하는 서비스입니다.

광고주가 지역과 타깃 조건을 입력하면, 게시대 주변 영향권의 인구 데이터를 집계해 조건에 맞는 게시대를 추천하는 것을 목표로 합니다.

- **영향권(3×3)** = 게시대가 속한 250m Cell + 주변 8칸

```text
유동인구 CSV ──┐
                ├→ Python 배치(전처리 · 3×3 매핑 · 집계) → 표준 CSV → psql \copy → PostgreSQL
게시대 CSV   ───┘                                                                  ↓
                                         React 화면(Naver Maps / Leaflet) ← Spring Boot API
```

> [!NOTE]
> 현재는 **데모 초안 단계**입니다.
>
> - 게시대는 실제 공공데이터, 유동인구는 아직 가짜 데이터로 동작합니다.
> - 전처리 → 매핑 → 적재 → 조회 API → 지도 화면까지 동작하며, 추천 API·문의 API는 아직 없습니다.
> - 바로 실행해 보려면 [빠른 시작](#빠른-시작)을 따라 하세요.

**문서**

- API 명세 v0.2.1: [명세서](docs/FLOWSIGN_API_SPEC_v0.2_board_centered.md) · [OpenAPI](docs/FLOWSIGN_OpenAPI_v0.2_board_centered.yaml)
- 작업 목록: [docs/TODO.md](docs/TODO.md)

## 목차

1. [기술 스택](#기술-스택)
2. [폴더 구조](#폴더-구조)
3. [사전 준비](#사전-준비)
4. [환경변수](#환경변수)
5. [데이터 준비](#데이터-준비)
6. [빠른 시작](#빠른-시작)
7. [문제 해결](#문제-해결)
8. [구성 요소별 사용법](#구성-요소별-사용법)
9. [현재 구현 상태](#현재-구현-상태)

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
│   ├── .env.example                # 지도 설정 예시
│   └── src/
│       ├── App.tsx                 # 화면 상태와 API 호출 흐름
│       ├── api.ts / types.ts       # fetch 기반 API 모듈, 응답 타입
│       ├── constants.ts            # 조건 코드·표시 이름
│       ├── components/             # FilterPanel(조건) / MapView(지도 툴바) / BoardDetail(상세)
│       └── map/                    # NaverMap, LeafletMap, 격자선 계산(common.ts)
│
├── backend/                        # Spring Boot
│   └── src/main/
│       ├── java/com/capstone/backend/
│       │   ├── health/             # GET /health
│       │   ├── common/             # 공통 응답 {data, meta}·오류 {error}, 코드값
│       │   ├── meta/               # GET /meta
│       │   ├── board/              # GET /boards, /boards/{id}, /boards/{id}/population, /boards/{id}/cells
│       │   ├── cell/               # GET /cells (지도 통신사 Cell 레이어)
│       │   ├── recommendation/     # /recommendations/search (자리만 있음)
│       │   └── inquiry/            # /inquiries (자리만 있음)
│       └── resources/
│           ├── application*.yml
│           └── db/migration/       # Flyway migration (테이블은 여기서만 생성)
│
├── python/                         # 데이터 배치 (CLI: data-processing)
│   ├── raw_mapping.example.json    # 원본 컬럼명·코드값 매핑 예시
│   └── src/data_processing/
│       ├── preprocess.py           # 유동인구 원본 → 표준 CSV (원본 형식은 매핑 파일로 읽음)
│       ├── board.py                # 게시대 원본(공공데이터포털) → 표준 board.csv
│       ├── fake_data.py            # 가짜 유동인구 CSV (게시대 3×3 주변 Cell만 만들 수 있음)
│       ├── mapping.py              # 게시대 ↔ Cell 3×3 매핑, board_population, grid_spec
│       ├── load.py                 # 표준 CSV → PostgreSQL 전체 재적재
│       └── scoring.py              # 추천 점수 참고 구현 (서비스 점수는 Spring)
│
├── database/
│   └── docker-compose.yml          # PostgreSQL 17 실행 설정
├── data/                           # 로컬 데이터 (Git 제외)
├── docs/                           # API 명세, OpenAPI, TODO
├── .env.example                    # DB 접속 정보 예시
├── AGENTS.md                       # AI 코딩 에이전트 작업 원칙
└── README.md
```

- 테이블 생성은 **Flyway migration만** 담당합니다.
- Python은 표준 CSV를 만들어 `psql \copy`로 **데이터만** 적재합니다.
- 원본 데이터와 실제 매핑 파일(`python/raw_mapping.json`)은 저장소에 두지 않습니다. → [데이터 준비](#데이터-준비)

---

## 사전 준비

| 도구 | 용도 |
| ---- | ---- |
| Git | 저장소 |
| JDK 21 | Backend |
| Node.js 20 이상 | Frontend |
| Docker Desktop | PostgreSQL 컨테이너, Backend 테스트(Testcontainers), 데이터 적재 |
| uv | Python 환경 관리 |

- Gradle과 Python 3.13은 따로 설치하지 않습니다. Gradle은 Gradle Wrapper가, Python은 uv가 관리합니다.
- 로컬에 `psql`도 설치할 필요가 없습니다. 적재할 때 컨테이너 안의 psql을 씁니다.

---

## 환경변수

### 1. 저장소 루트 `.env` — DB 접속 (Backend·Docker Compose 공용)

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

### 2. `frontend/.env` — 지도 (선택)

```cmd
copy frontend\.env.example frontend\.env
```

| 변수                       | 설명                                                  |
| -------------------------- | ----------------------------------------------------- |
| `VITE_MAP_PROVIDER`        | 첫 화면 지도 `naver` / `leaflet` (기본 `leaflet`)      |
| `VITE_NAVER_MAP_CLIENT_ID` | NAVER Cloud Platform Maps Client ID (`ncpKeyId`)      |

- **Leaflet**은 키 없이 OpenStreetMap 타일로 동작합니다.
- **Naver 지도**는 Client ID가 없으면 안내 문구만 표시합니다.
  NCP 콘솔의 Maps 애플리케이션에 웹 서비스 URL `http://localhost:5173`도 등록해야 합니다.

---

## 데이터 준비

> [!CAUTION]
> **공개 저장소입니다. 원본 데이터와 그 결과물은 절대 커밋하지 않습니다.**
>
> - `data/` 폴더, 원본·결과 파일명, 데이터 파일 형식(`*.csv`, `*.xlsx` 등)은 `.gitignore`로 제외되어 있습니다.
> - 그래도 커밋 전에는 `git status`로 데이터 파일이 섞이지 않았는지 한 번 더 확인합니다.

아래 위치·이름으로 **각자 로컬에** 둡니다. 최초 1회만 하면 됩니다.

| 파일 | 위치·이름 | 필요한 때 |
| ---- | --------- | --------- |
| 게시대 원본 | `data/raw/board_raw.csv` | 항상 |
| 통신사 유동인구 원본 | `data/raw/population_raw_1.csv`, `population_raw_2.csv`, … | 실제 유동인구로 실행할 때 |
| 원본 매핑 파일 | `python/raw_mapping.json` | 실제 유동인구로 실행할 때 |

**게시대 원본** — `data/raw/board_raw.csv`

- 공공데이터포털 「전국현수막게시대표준데이터」 CSV를 내려받아 이 이름으로 저장합니다.
- 인코딩(CP949)은 바꾸지 않습니다.

**통신사 유동인구 원본** — `data/raw/population_raw_1.csv`, `population_raw_2.csv`, …

- 원본이 여러 CSV로 나뉘어 있으면 `_1`, `_2`, `_3`처럼 번호를 붙여 모두 둡니다.
- 파일마다 첫 줄에 같은 헤더가 있어야 합니다. 파일끼리 같은 행이 겹치면 안 됩니다(두 번 합산됨).
- 팀 내부에서만 전달받습니다.
- 저장소·메신저·외부 서비스에 올리지 않습니다.
- 없으면 가짜 데이터로 실행합니다. → [2-A](#2-a-가짜-유동인구로-실행)

**원본 매핑 파일** — `python/raw_mapping.json`

- `python/raw_mapping.example.json`을 복사해 실제 원본 컬럼명·코드값을 채웁니다.
- 이 파일도 커밋하지 않습니다.

준비가 끝나면 `data/`는 이렇게 됩니다.

```text
data/
├── raw/
│   ├── board_raw.csv          # 게시대 원본 (직접 준비)
│   ├── population_raw_1.csv   # 유동인구 원본 (실제 데이터가 있을 때만)
│   ├── population_raw_2.csv   #   여러 파일로 나뉘어 있으면 번호를 이어 붙임
│   ├── board.csv              # ← 명령이 만드는 파일
│   └── fake_population.csv    # ← 명령이 만드는 파일 (가짜 데이터로 실행할 때)
└── processed/                 # ← 전처리·매핑 결과 (적재용 표준 CSV)
```

---

## 빠른 시작

터미널 3개를 씁니다. (Windows `cmd` 기준)

### 1. DB + Backend 실행

```cmd
docker compose --env-file .env -f database/docker-compose.yml up -d
cd backend
gradlew.bat bootRun
```

- Backend가 처음 뜰 때 Flyway가 테이블을 만듭니다.
- `Started BackendApplication`이 보이면 http://localhost:8080/api/v1/health 가 `{"status":"UP"}`를 반환합니다.

### 2. 데이터 변환 · 적재

Backend를 켠 상태로 **다른 터미널**에서 실행합니다. 유동인구 데이터에 따라 2-A 또는 2-B 중 하나를 씁니다.

#### 2-A. 가짜 유동인구로 실행

실제 유동인구 원본이 없을 때 씁니다.

```cmd
cd python
uv sync
uv run data-processing board      ../data/raw/board_raw.csv ../data/raw/board.csv
uv run data-processing fake-data  ../data/raw/fake_population.csv --boards ../data/raw/board.csv
uv run data-processing preprocess ../data/raw/fake_population.csv ../data/processed
uv run data-processing map        ../data/processed ../data/raw/board.csv
uv run data-processing load       ../data/processed ../data/raw/board.csv
```

| 명령 | 하는 일 |
| ---- | ------- |
| `uv sync` | Python 패키지 설치 (최초 1회) |
| `board` | 게시대 원본에서 안양·군포·시흥 게시대만 골라 표준 `board.csv`로 변환 |
| `fake-data` | 게시대 3×3 주변 Cell에 대해 원본 형식의 가짜 유동인구 CSV 생성 |
| `preprocess` | 유동인구 원본 정제 → `processed/`에 `population_fact.csv`, `cell.csv`, 품질 리포트 |
| `map` | 게시대 ↔ 3×3 Cell 매핑, 게시대별 인구 합계, 격자선 기준값 계산 |
| `load` | 6개 테이블을 비우고 표준 CSV를 PostgreSQL에 다시 적재 |

게시대 445개와 그 주변 가짜 Cell 약 1,400개(약 290만 행)가 만들어지며, 전체 2분 안팎이 걸립니다.

#### 2-B. 실제 유동인구로 실행

`population_raw_*.csv`와 `raw_mapping.json`을 [준비](#데이터-준비)했을 때 씁니다. 2-A에서 `fake-data`가 빠지고, `preprocess`의 입력이 실제 원본으로 바뀝니다.

```cmd
cd python
uv sync
uv run data-processing board      ../data/raw/board_raw.csv ../data/raw/board.csv
uv run data-processing preprocess ../data/raw/population_raw_1.csv ../data/raw/population_raw_2.csv ../data/processed
uv run data-processing map        ../data/processed ../data/raw/board.csv
uv run data-processing load       ../data/processed ../data/raw/board.csv
```

- 원본 파일은 **모두 나열**하고 마지막에 출력 폴더를 씁니다. 파일이 3개면 `population_raw_3.csv`를 출력 폴더 앞에 추가합니다.
  여러 파일은 순서대로 이어 읽어 결과 하나로 합칩니다. 헤더가 다른 파일이 있으면 오류로 멈춥니다.
- 원본이 UTF-8이 아니면 `preprocess`에 `--encoding`을 붙입니다. (예: `--encoding cp949`)
- 적재 후 `backend/src/main/resources/application.yml`의 `flowsign.dataset.synthetic`을 `false`로 바꿉니다.
  API 응답의 `meta.syntheticData`에 반영됩니다.

> [!WARNING]
> 실제 데이터로 만든 `data/processed/`의 결과 파일과 `preprocess_report.json`(행 수·NULL 수·기간 등 집계)도
> 실제 데이터에서 나온 것입니다. 저장소나 외부에 올리지 않습니다.

> [!TIP]
> `load`는 기존 데이터를 모두 지우고 다시 넣으므로 몇 번이든 반복해도 됩니다.

### 3. Frontend 실행

```cmd
cd frontend
npm install
npm run dev
```

http://localhost:5173 을 엽니다.

| 조작 | 결과 |
| ---- | ---- |
| 상단 `Naver` / `Leaflet` 버튼 | 지도 전환 |
| 게시대 마커 클릭 | 오른쪽에 상세 정보와 3×3 영향권 인구 차트 |
| `250m 격자선` 체크 | 격자선 표시 |
| `통신사 Cell` 체크 | 화면 범위의 데이터 Cell을 사각형으로 표시 (인구값 없음, 너무 넓으면 확대 안내) |
| `3×3 Cell 보기` 체크 | 선택한 게시대의 3×3 Cell 표시 |
| 왼쪽 조건 변경 후 `조건 적용` | 상세 인구 다시 조회 |

**데모 링크** — 게시대 선택·격자선·3×3·통신사 Cell을 켠 상태로 시작합니다. `&map=naver`를 붙이면 Naver 지도로 시작합니다.

```text
http://localhost:5173/?board=3&grid=1&cells=1&allcells=1
```

---

## 문제 해결

**화면에 "데이터를 불러오지 못했습니다"**

- Backend가 꺼져 있거나 데이터 적재 전입니다. [빠른 시작](#빠른-시작)의 1·2단계를 확인합니다.

**`Port 8080 was already in use`**

- 이전 Backend가 남아 있습니다. 종료하거나 다른 포트로 띄우고, Frontend 프록시도 그 포트로 맞춥니다.

  ```cmd
  gradlew.bat bootRun --args=--server.port=8081
  set API_PROXY_TARGET=http://localhost:8081&& npm run dev
  ```

**`load`에서 `docker` 오류**

- Docker Desktop과 `telecom-postgres` 컨테이너가 떠 있는지 확인합니다.
- DB 이름·사용자를 바꿨다면 `--db`, `--user`, `--container` 옵션을 줍니다.

**`load`에서 테이블이 없다는 오류**

- Backend를 한 번도 실행하지 않아 Flyway migration이 적용되지 않은 상태입니다. Backend를 먼저 실행합니다.

**Backend 시작 시 Flyway 검증(checksum) 오류**

- DB 볼륨에 예전 스키마가 남아 있습니다. 볼륨을 지우고 1·2단계를 다시 실행합니다.

  ```cmd
  docker compose --env-file .env -f database/docker-compose.yml down -v
  ```

  > `down -v`는 DB 데이터를 전부 삭제합니다.

**Naver 지도에 안내 문구만 표시**

- `frontend/.env`의 `VITE_NAVER_MAP_CLIENT_ID`와 NCP 웹 서비스 URL 등록을 확인하고 dev 서버를 재시작합니다.

---

## 구성 요소별 사용법

### Python 배치 (`python/`, CLI `data-processing`)

```text
data-processing board      <원본.csv> <출력 board.csv> [--encoding cp949]
data-processing fake-data  <출력.csv> --boards <board.csv> [--seed 42] [--mapping 파일]
data-processing preprocess <원본.csv> [원본2.csv ...] <출력폴더> [--encoding cp949] [--chunksize N] [--mapping 파일]
data-processing map        <전처리폴더> <board.csv>
data-processing load       <전처리폴더> <board.csv> [--container] [--db] [--user]
```

| 명령 | 하는 일 |
| ---- | ------- |
| `board` | 게시대 원본(공공데이터포털)에서 대상 3개 시만 골라 표준 `board.csv`로 변환 |
| `fake-data` | 게시대들의 3×3 Cell에 대해 매핑 파일 형식의 가짜 유동인구 원본 CSV 생성 (실제 원본이 없을 때 개발용) |
| `preprocess` | 원본 정제 → `population_fact.csv`, `cell.csv`, `preprocess_report.json`. 원본이 여러 파일이면 모두 받아 하나로 합침 |
| `map` | 3×3 매핑 → `board_cell.csv`, `board_population.csv`, `grid_spec.csv` |
| `load` | 6개 테이블을 비우고 `\copy`로 재적재 |

**매핑 파일**

- 원본 컬럼명·코드값은 매핑 파일로 읽습니다.
- `python/raw_mapping.json`이 있으면 그것을, 없으면 `raw_mapping.example.json`(가짜 데이터 형식)을 씁니다.
- `fake-data`와 `preprocess`는 같은 매핑 파일을 쓰므로, 로컬에 `raw_mapping.json`이 있어도 가짜 데이터 명령은 그대로 동작합니다.

**전처리 NULL 규칙** (자세한 내용은 명세 1.1)

| 대상 | 처리 |
| ---- | ---- |
| 분류 컬럼(주중구분·성별·연령·시간대) NULL | `UNKNOWN`으로 보존 |
| `cell_id`·`base_month` NULL | 행 제외 |
| 인구값 NULL | 그대로 둠 |
| 매핑되지 않는 코드, 숫자가 아닌 값 | 오류로 중단 |

`preprocess_report.json`에는 행 수·NULL 수 같은 집계만 들어가고 원본 행은 들어가지 않습니다.

### Backend (`backend/`)

```cmd
gradlew.bat bootRun
gradlew.bat build
gradlew.bat test
```

- `bootRun`: 개발 서버 http://localhost:8080
- `test`: Testcontainers가 임시 PostgreSQL을 띄웁니다. Docker는 필요하지만 `.env`나 로컬 DB는 필요 없습니다.
- DB 구조를 바꿀 때는 기존 migration을 고치지 않고 `V2__...sql`처럼 새 파일을 추가합니다. (`ddl-auto: validate`)

**구현된 API** — 응답 형식은 명세 2장 `{data, meta}` / `{error}`

| Method | URL | 설명 |
| ------ | --- | ---- |
| GET | `/api/v1/health` | 상태 확인 |
| GET | `/api/v1/meta` | 필터 선택지, 격자 기준값(`grid`), 데이터 상태 |
| GET | `/api/v1/boards` | 게시대 마커 목록 (`cities`, bbox, `purpose` 필터) |
| GET | `/api/v1/boards/{id}` | 게시대 상세 |
| GET | `/api/v1/boards/{id}/population` | 3×3 합계, 시간대·월별, 성별·연령·유동·서비스 분포 |
| GET | `/api/v1/boards/{id}/cells` | 3×3 Cell 중심·사각형(GeoJSON)·Cell별 인구 |
| GET | `/api/v1/cells` | 화면 범위(`west`, `south`, `east`, `north`) 안 통신사 Cell 중심 (인구값 없음, 최대 3000개) |

`population`·`cells`가 받는 조건 쿼리: `gender`, `ageGroups`, `flowType`, `timeBands`, `periodKind`(+`season`/`month`), `dayType`

```text
/api/v1/boards/3/population?gender=FEMALE&ageGroups=AGE_20S,AGE_30S&periodKind=SEASON&season=SUMMER
```

### Frontend (`frontend/`)

```cmd
npm run dev
npm run build
npm run lint
```

- `dev`: http://localhost:5173 — `/api` 요청은 Vite 프록시가 Backend로 전달합니다. (CORS 대신 프록시 사용)
- 프록시 대상은 `API_PROXY_TARGET` 환경변수로 바꿀 수 있습니다. (기본 `http://localhost:8080`)
- HTTP 호출은 `fetch`만 쓰고, 10초 타임아웃을 둡니다.
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
