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
> 현재는 **데모 초안 단계**입니다. 게시대는 실제 공공데이터, 유동인구는 가짜 또는 실제 데이터로 동작하며, 추천 API·문의 API는 아직 없습니다.

**문서**

- API 명세 v0.2.1: [명세서](docs/FLOWSIGN_API_SPEC_v0.2_board_centered.md) · [OpenAPI](docs/FLOWSIGN_OpenAPI_v0.2_board_centered.yaml)
- 구현 현황·남은 작업: [docs/TODO.md](docs/TODO.md)
- 데모에서 임시로 정한 규칙: [명세 11장](docs/FLOWSIGN_API_SPEC_v0.2_board_centered.md#11-데모-구현-현황-2026-11-07)

## 목차

1. [기술 스택](#기술-스택)
2. [사전 준비](#사전-준비)
3. [환경변수](#환경변수)
4. [실행 방법](#실행-방법)
5. [데이터와 보안](#데이터와-보안)
6. [문제 해결](#문제-해결)
7. [개발 참고](#개발-참고)

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

## 실행 방법

터미널 3개를 씁니다. (Windows `cmd` 기준) 1 → 2 → 3 → 4 순서로 진행합니다.

### 1. DB + Backend 실행

```cmd
docker compose --env-file .env -f database/docker-compose.yml up -d
cd backend
gradlew.bat bootRun
```

`Started BackendApplication`이 보이면 준비된 것입니다. 처음 뜰 때 Flyway가 테이블을 만들기 때문에, 데이터 적재 전에 한 번은 실행해야 합니다.

### 2. 데이터 생성 · 적재

Backend를 켠 상태로 **다른 터미널**의 `python` 폴더에서 실행합니다. 2.1과 2.2 중 하나를 고릅니다.

```cmd
cd python
uv sync
```

`uv sync`는 최초 1회만 실행합니다.

#### 2.1. 가짜 데이터

게시대 원본만 있으면 됩니다. 공공데이터포털 「전국현수막게시대표준데이터」 CSV를 내려받아 `data/raw/board_raw.csv`로 저장합니다(인코딩 CP949 그대로).

```cmd
uv run data-processing build fake --load
```

실제 게시대 445개와 그 주변 가짜 Cell·인구를 만들어 `data/generated_fake/`에 저장하고 DB에 적재합니다. 1~2분 걸립니다.

#### 2.2. 실제 데이터

아래 파일을 각자 로컬에 둡니다.

| 파일 | 위치·이름 | 비고 |
| ---- | --------- | ---- |
| 게시대 원본 | `data/raw/board_raw.csv` | 2.1과 같음 |
| 통신사 유동인구 원본 | `data/raw/population_raw_1.csv`, `_2`, `_3` … | 여러 파일이면 1부터 빠짐없이 번호. 파일마다 같은 헤더, 파일끼리 겹치는 행 없음 |
| 원본 매핑 파일 | `python/raw_mapping.json` | `raw_mapping.example.json`을 복사해 실제 컬럼명·코드값을 채움 |

1. 세트만 만들고 결과를 확인합니다. 원본이 UTF-8이 아니면 `--encoding cp949`를 붙입니다.
   ```cmd
   uv run data-processing build real
   ```
2. 출력에서 확인합니다.
   - `[0/5] 원본 확인`의 **통신사 원본 개수가 실제로 받은 파일 수와 같은지** (마지막 번호 파일이 빠진 것은 자동으로 알 수 없음)
   - `[4/5] 매핑`의 9칸 게시대 수와 격자 검증. `NOT_VERIFIED`면 `250m 격자선`만 안 나오고, Cell 사각형과 매핑·집계에는 영향이 없습니다.
   - `data/generated_real/preprocess_report.json`의 기간·NULL 수 등
3. 문제없으면 적재합니다.
   ```cmd
   uv run data-processing load real
   ```

적재가 끝나면 Backend가 DB에 기록된 세트 종류(실제/가짜)를 읽어 응답에 자동으로 표시합니다. 설정을 바꾸거나 Backend를 재시작할 필요가 없습니다.

가짜 데이터로 돌아가려면 `uv run data-processing load fake`만 다시 실행합니다. 두 세트는 폴더가 따로라 서로 지워지지 않습니다.

### 3. Frontend 실행

세 번째 터미널에서 실행합니다.

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

## 데이터와 보안

> [!CAUTION]
> **공개 저장소입니다. 원본 데이터와 그 결과물은 절대 커밋하지 않습니다.**
>
> - `data/` 폴더, 원본·결과 파일명, 데이터 파일 형식(`*.csv`, `*.xlsx` 등)은 `.gitignore`로 제외되어 있습니다. 그래도 커밋 전에 `git status`를 확인합니다.
> - 통신사 원본은 팀 내부에서만 전달받고, 저장소·메신저·외부 서비스에 올리지 않습니다.
> - `data/generated_real/`의 결과 파일과 `preprocess_report.json`(행 수·기간 등 집계)도 실제 데이터에서 나온 것이므로 외부에 올리지 않습니다.
> - 오류 메시지에는 실제 코드값·컬럼명이 찍힐 수 있으니 공유할 때 가립니다.

```text
data/
├── raw/              직접 넣는 원본만 (어떤 명령도 여기에 쓰지 않음)
├── generated_fake/   build fake 결과: 실제 게시대 + 가짜 통신사 데이터
└── generated_real/   build real 결과: 실제 게시대 + 실제 통신사 데이터
```

- 사람이 넣는 곳은 `raw/`뿐입니다. `generated_*/`는 언제든 지우고 다시 만들 수 있습니다.
- `generated_fake/`의 Cell·인구·매핑 결과는 가짜입니다. 실제 매핑 결과로 쓰지 않습니다.
- DB에는 한 번에 한 세트만 들어 있습니다.

---

## 문제 해결

**화면에 "데이터를 불러오지 못했습니다"**

- Backend가 꺼져 있거나 데이터 적재 전입니다. [실행 방법](#실행-방법)의 1·2단계를 확인합니다.

**`Port 8080 was already in use`**

- 이전 Backend가 남아 있습니다. 종료하거나 다른 포트로 띄우고, Frontend 프록시도 그 포트로 맞춥니다.

  ```cmd
  gradlew.bat bootRun --args=--server.port=8081
  set API_PROXY_TARGET=http://localhost:8081&& npm run dev
  ```

**`중단: ...`으로 끝나며 아무것도 만들어지지 않음**

- 필요한 원본·매핑 파일이 없거나, 이름 규칙이 다르거나, 세트 검증에 실패한 경우입니다. 메시지에 나온 파일을 확인합니다.
- 이때 기존 `generated_<모드>/` 세트와 DB 데이터는 바뀌지 않습니다.

**`load`에서 `docker` 오류**

- Docker Desktop과 `telecom-postgres` 컨테이너가 떠 있는지 확인합니다.
- DB 이름·사용자를 바꿨다면 `--db`, `--user`, `--container` 옵션을 줍니다.

**`load`에서 테이블이 없다는 오류** (DB 적재 실패, 롤백됨)

- Backend를 한 번도 실행하지 않았거나 코드를 받은 뒤 다시 실행하지 않아, Flyway migration이 적용되지 않은 상태입니다. Backend를 먼저 (다시) 실행합니다.

**Backend 시작 시 Flyway 검증(checksum) 오류**

- DB 볼륨에 예전 스키마가 남아 있습니다. 볼륨을 지우고 1·2단계를 다시 실행합니다.

  ```cmd
  docker compose --env-file .env -f database/docker-compose.yml down -v
  ```

  > `down -v`는 DB 데이터를 전부 삭제합니다.

**Naver 지도에 안내 문구만 표시**

- `frontend/.env`의 `VITE_NAVER_MAP_CLIENT_ID`와 NCP 웹 서비스 URL 등록을 확인하고 dev 서버를 재시작합니다.

---

## 개발 참고

<details>
<summary><b>폴더 구조</b></summary>

```text
flowsign/
├── frontend/                       # React + TypeScript + Vite
│   ├── .env.example                # 지도 설정 예시
│   └── src/
│       ├── App.tsx                 # 화면 상태와 API 호출 흐름
│       ├── api.ts / types.ts       # fetch 기반 API 모듈, 응답 타입
│       ├── constants.ts            # 조건 코드·표시 이름
│       ├── components/             # FilterPanel(조건) / MapView(지도 툴바) / BoardDetail(상세)
│       └── map/                    # NaverMap, LeafletMap, 격자선·Cell 사각형 계산(common.ts)
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
│       ├── __init__.py             # CLI: build fake|real, load fake|real
│       ├── pipeline.py             # 전체 흐름(원본 확인 → 생성 → 검증 → 교체 → 적재). 맨 위에 부품 지도
│       ├── board.py                #   1단계: 게시대 원본(공공데이터포털) → board.csv
│       ├── fake_data.py            #   2단계(fake만): 가짜 통신사 원본 population_fake.csv
│       ├── preprocess.py           #   3단계: 통신사 원본 → cell, population_fact, 리포트
│       ├── mapping.py              #   4단계: 게시대 ↔ 3×3 Cell → board_cell, board_population, grid_spec
│       └── load.py                 #   적재: 세트 → PostgreSQL (트랜잭션 하나로 교체)
│
├── database/
│   └── docker-compose.yml          # PostgreSQL 17 실행 설정
├── data/                           # 로컬 데이터 (Git 제외) → 데이터와 보안
├── docs/                           # API 명세, OpenAPI, TODO
├── .env.example                    # DB 접속 정보 예시
├── AGENTS.md                       # AI 코딩 에이전트 작업 원칙
└── README.md
```

- 테이블 생성은 **Flyway migration만** 담당합니다. Python은 표준 CSV를 만들어 `psql \copy`로 **데이터만** 적재합니다.

</details>

<details>
<summary><b>데이터 세트 파일 구성</b></summary>

```text
data/
├── raw/
│   ├── board_raw.csv
│   ├── population_raw_1.csv
│   └── population_raw_2.csv
├── generated_real/
│   ├── board.csv, cell.csv, population_fact.csv
│   ├── board_cell.csv, board_population.csv, grid_spec.csv
│   └── preprocess_report.json
└── generated_fake/
    ├── board.csv, population_fake.csv(가짜 통신사 원본), cell.csv, population_fact.csv
    ├── board_cell.csv(실제 게시대 × 가짜 Cell), board_population.csv, grid_spec.csv
    └── preprocess_report.json
```

- 두 세트는 각각 **독립된 한 벌**입니다. CSV 파일명은 DB 테이블명과 같습니다.
- `preprocess_report.json`에는 품질 집계와 함께 출처(`dataset`: `real`/`fake`, 입력 파일 이름)가 기록됩니다.

</details>

<details>
<summary><b><code>build</code> 단계와 실제·가짜 혼합 방지</b></summary>

| 단계 | 하는 일 |
| ---- | ------- |
| 0. 원본 확인 | 모드에 필요한 원본이 모두 있는지 확인. 없으면 빠진 파일을 모두 출력하고 **아무것도 만들지 않고** 중단 |
| 1. 게시대 변환 | `board_raw.csv`에서 안양·군포·시흥 게시대만 골라 표준 `board.csv`로 변환 |
| 2. 통신사 데이터 | fake: 게시대 3×3 주변에 가짜 통신사 원본 생성 / real: `population_raw_*.csv`를 번호순으로 사용 |
| 3. 전처리 | 통신사 원본 정제 → `population_fact.csv`, `cell.csv`, `preprocess_report.json` |
| 4. 매핑 | 게시대 ↔ 3×3 Cell 매핑, 게시대별 인구 합계, 격자선 기준값 |
| 5. 세트 검증 | 파일이 모두 있는지, 출처가 모드와 맞는지, 세트 안 ID가 서로 맞는지 확인 |
| 6. 교체 | 검증까지 통과해야 기존 `generated_<모드>/`와 바꿈 |

**혼합 방지 장치**

- 결과 폴더는 모드로 정해지며 경로를 입력하지 않습니다.
- 실제 모드는 통신사 원본이나 매핑 파일이 없으면 중단합니다. 가짜 데이터나 예시 매핑으로 대신하지 않습니다.
- 가짜 모드도 게시대 원본이 없으면 중단합니다. 임의 게시대를 만들지 않습니다.
- 생성은 임시 폴더(`generated_<모드>.building`)에서 하고, 실패하면 임시 폴더만 지웁니다. 기존 세트는 그대로입니다.
- `load`는 검증을 통과한 세트만 적재합니다. 적재는 트랜잭션 하나로 실행되어, 중간에 실패하면 기존 DB 데이터가 그대로 남습니다.

**지도 표시**

- Cell 사각형은 원본 경계가 아니라 **중심 ±125m 근사**로 그립니다. 격자 검증 여부와 관계없이 사각형으로 보입니다.
- `250m 격자선`은 격자 검증이 `VERIFIED`일 때만 표시됩니다.

</details>

<details>
<summary><b>Python 배치 (<code>python/</code>, CLI <code>data-processing</code>)</b></summary>

```text
data-processing build fake [--load]
data-processing build real [--encoding cp949] [--load]
data-processing load  fake|real
```

DB 접속 옵션 `--container`(기본 `telecom-postgres`), `--db`(기본 `telecom`), `--user`(기본 `telecom`)는 `build --load`와 `load` 모두에 줄 수 있습니다.

| 명령 | 하는 일 |
| ---- | ------- |
| `build fake` | `data/raw/board_raw.csv`로 `data/generated_fake/` 생성 (가짜 통신사 데이터 포함) |
| `build real` | `data/raw/`의 게시대·통신사 원본과 `python/raw_mapping.json`으로 `data/generated_real/` 생성 |
| `load fake` / `load real` | 해당 세트를 검증한 뒤 6개 테이블을 트랜잭션 하나로 교체 |

- 단계별 처리는 `pipeline.py`가 순서대로 부릅니다. 단계별 명령은 경로 실수로 세트가 섞일 수 있어 두지 않습니다.

**매핑 파일**

- 원본 컬럼명·코드값은 매핑 파일로 읽습니다.
- 실제 모드는 `python/raw_mapping.json`이 반드시 있어야 합니다.
- 가짜 모드는 `raw_mapping.json`이 있으면 그 형식으로, 없으면 `raw_mapping.example.json` 형식으로 가짜 원본을 만들고 같은 파일로 읽습니다.

**전처리 NULL 규칙** (자세한 내용은 명세 1.1)

| 대상 | 처리 |
| ---- | ---- |
| 분류 컬럼(주중구분·성별·연령·시간대) NULL | `UNKNOWN`으로 보존 |
| `cell_id`·`base_month` NULL | 행 제외 |
| 인구값 NULL | 그대로 둠 |
| 매핑되지 않는 코드, 숫자가 아닌 값 | 오류로 중단 |

</details>

<details>
<summary><b>Backend (<code>backend/</code>)와 API</b></summary>

```cmd
gradlew.bat bootRun
gradlew.bat build
gradlew.bat test
```

- `bootRun`: 개발 서버 http://localhost:8080
- `test`: Testcontainers가 임시 PostgreSQL을 띄웁니다. Docker는 필요하지만 `.env`나 로컬 DB는 필요 없습니다.
- DB 구조를 바꿀 때는 기존 migration을 고치지 않고 `V3__...sql`처럼 새 파일을 추가합니다. (`ddl-auto: validate`)

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

</details>

<details>
<summary><b>Frontend (<code>frontend/</code>)</b></summary>

```cmd
npm run dev
npm run build
npm run lint
```

- `dev`: http://localhost:5173 — `/api` 요청은 Vite 프록시가 Backend로 전달합니다. (CORS 대신 프록시 사용)
- 프록시 대상은 `API_PROXY_TARGET` 환경변수로 바꿀 수 있습니다. (기본 `http://localhost:8080`)
- HTTP 호출은 `fetch`만 쓰고, 10초 타임아웃을 둡니다.
- 차트 라이브러리는 아직 정하지 않아 상세 차트는 CSS 막대로 그립니다.

</details>

> 지도 전체 인구 표시 기능과 `grid250_population` 테이블은 범위에서 제외되었습니다.
