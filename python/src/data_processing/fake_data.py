"""가짜 유동인구 원본 CSV 생성기.

실제 원본을 개발 환경에 둘 수 없으므로, 전처리기 → 매핑 → 적재 → API 개발과 성능 확인을
이 가짜 데이터로 한다. 값은 모두 난수이며 실제 인구와 무관하다.

컬럼명과 코드값은 전처리기와 같은 매핑 파일(raw_mapping.json, 없으면 예시 파일)을 따른다.
  - 250m Cell, Cell 중심 위경도
  - 12개월 × 주중/주말 × 성별 × 연령 6구간 × 시간대 7구간(2시간)
  - 인구 6종은 소수점 숫자, 0과 NULL이 섞임
  - 성별·연령 NULL 포함 (빈 칸으로 기록)
지역은 대상 시 근처의 가상 격자이며 동 이름은 가상값이다.
"""

from pathlib import Path

import numpy as np
import pandas as pd

from data_processing.board import BOARD_COLUMNS
from data_processing.preprocess import RawMapping, load_mapping

# 250m에 해당하는 위경도 간격 (위도 37.4° 근처 근사값)
LAT_STEP = 0.002246
LNG_STEP = 0.002827
# 가상 격자 범위 (대상 3개 시를 대략 덮는 사각형)
ORIGIN_LAT, ORIGIN_LNG = 37.30, 126.73
GRID_ROWS, GRID_COLS = 67, 89

MONTHS = [f"2025{m:02d}" for m in range(1, 13)]
# 표준 코드. CSV에 쓸 때 매핑 파일의 원본 코드로 바꾼다.
DAY_TYPES = ["WEEKDAY", "WEEKEND"]
GENDERS = ["MALE", "FEMALE"]
AGES = ["UNDER_20", "AGE_20S", "AGE_30S", "AGE_40S", "AGE_50S", "AGE_60_PLUS"]
TIME_SLOTS = ["06_08", "08_10", "10_12", "12_14", "14_16", "16_18", "18_20"]
POPULATION_SHARES = {"stay": 0.5, "walk": 0.3, "drive": 0.2, "residence": 0.4, "work": 0.3, "visit": 0.3}

# 시간대별 상대 유동 (출퇴근·점심 시간이 높게)
TIME_WEIGHT = np.array([0.6, 1.3, 0.9, 1.1, 0.9, 1.0, 1.4])
AGE_WEIGHT = np.array([0.7, 1.1, 1.2, 1.2, 1.0, 0.9])

ROWS_PER_CELL = len(MONTHS) * len(DAY_TYPES) * len(GENDERS) * len(AGES) * len(TIME_SLOTS)
# 실측: 예시 매핑 기준 행당 약 105바이트 (매핑 파일의 컬럼명·코드 길이에 따라 달라짐)
APPROX_BYTES_PER_ROW = 105


def cells_for_size(target_bytes: int) -> int:
    """목표 파일 크기에 맞는 Cell 수를 계산한다."""
    return max(1, round(target_bytes / (ROWS_PER_CELL * APPROX_BYTES_PER_ROW)))


def _make_cells(n_cells: int, rng: np.random.Generator) -> pd.DataFrame:
    if n_cells > GRID_ROWS * GRID_COLS:
        raise ValueError(f"Cell 수는 최대 {GRID_ROWS * GRID_COLS}개입니다.")
    # Cell이 이어져 있어야 3×3 이웃이 생긴다. 격자 중앙에서 가까운 칸부터 고른다.
    all_rows, all_cols = np.divmod(np.arange(GRID_ROWS * GRID_COLS), GRID_COLS)
    dist = np.hypot(all_rows - GRID_ROWS / 2, all_cols - GRID_COLS / 2) + rng.uniform(0, 1.5, all_rows.size)
    picks = np.sort(np.argsort(dist)[:n_cells])
    rows, cols = np.divmod(picks, GRID_COLS)
    # 서쪽은 시흥시, 동쪽 북부는 안양시, 동쪽 남부는 군포시
    city = np.where(cols < GRID_COLS * 0.45, "시흥시", np.where(rows >= GRID_ROWS * 0.45, "안양시", "군포시"))
    return pd.DataFrame({
        "cell_id": [f"C{r:03d}{c:03d}" for r, c in zip(rows, cols)],
        "longitude": np.round(ORIGIN_LNG + (cols + 0.5) * LNG_STEP, 6),
        "latitude": np.round(ORIGIN_LAT + (rows + 0.5) * LAT_STEP, 6),
        "city_name": city,
        "dong_name": [f"가상{(r // 8) * 12 + c // 8 + 1}동" for r, c in zip(rows, cols)],
        # Cell마다 인구 규모를 다르게 (소수 Cell에 인구 집중)
        "scale": rng.lognormal(mean=2.0, sigma=1.0, size=n_cells),
    })


def _to_raw_codes(values: np.ndarray, code_map: dict[str, str]) -> np.ndarray:
    """표준 코드를 매핑 파일의 원본 코드로 바꾼다."""
    reverse = {std: raw for raw, std in code_map.items()}
    return np.array([reverse[v] for v in values], dtype=object)


def _rows_for_cells(
    cells: pd.DataFrame, rng: np.random.Generator, null_rate: float, mapping: RawMapping
) -> pd.DataFrame:
    dims = pd.MultiIndex.from_product(
        [range(len(MONTHS)), range(len(DAY_TYPES)), range(len(GENDERS)), range(len(AGES)), range(len(TIME_SLOTS))],
        names=["m", "d", "g", "a", "t"],
    ).to_frame(index=False)
    n_dims = len(dims)
    idx = np.repeat(np.arange(len(cells)), n_dims)
    d = dims.iloc[np.tile(np.arange(n_dims), len(cells))].reset_index(drop=True)
    n = len(d)

    base = (
        cells["scale"].to_numpy()[idx]
        * TIME_WEIGHT[d["t"].to_numpy()]
        * AGE_WEIGHT[d["a"].to_numpy()]
        * np.where(d["d"].to_numpy() == 1, 0.8, 1.0)
        * rng.lognormal(0.0, 0.4, size=n)
    )

    codes = mapping.codes
    out = pd.DataFrame({
        "base_month": np.array(MONTHS)[d["m"].to_numpy()],
        "city_name": cells["city_name"].to_numpy()[idx],
        "dong_name": cells["dong_name"].to_numpy()[idx],
        "cell_id": cells["cell_id"].to_numpy()[idx],
        "longitude": cells["longitude"].to_numpy()[idx],
        "latitude": cells["latitude"].to_numpy()[idx],
        "gender": _to_raw_codes(np.array(GENDERS)[d["g"].to_numpy()], codes["gender"]),
        "age_group": _to_raw_codes(np.array(AGES)[d["a"].to_numpy()], codes["age_group"]),
        "time_slot": _to_raw_codes(np.array(TIME_SLOTS)[d["t"].to_numpy()], codes["time_slot"]),
    })
    if "day_type" in mapping.columns:
        out["day_type"] = _to_raw_codes(np.array(DAY_TYPES)[d["d"].to_numpy()], codes["day_type"])
    for col, share in POPULATION_SHARES.items():
        values = np.round(base * share * rng.uniform(0.7, 1.3, size=n), 2)
        values[rng.random(n) < 0.08] = 0.0          # 관측된 0
        values[rng.random(n) < null_rate] = np.nan  # 값 없음
        out[col] = values

    # 성별·연령 NULL (빈 칸)
    out.loc[rng.random(n) < null_rate * 4, "gender"] = None
    out.loc[rng.random(n) < null_rate * 4, "age_group"] = None
    columns = [c for c in mapping.columns if c in out.columns]
    return out[columns].rename(columns=mapping.columns)


def generate_fake_csv(
    output_path: Path,
    n_cells: int,
    seed: int = 42,
    null_rate: float = 0.005,
    cells_per_batch: int = 100,
    mapping_path: Path | None = None,
) -> int:
    """가짜 원본 CSV를 만든다. 쓴 행 수를 반환한다. 메모리를 아끼려고 Cell 묶음 단위로 쓴다."""
    mapping = load_mapping(mapping_path)
    rng = np.random.default_rng(seed)
    cells = _make_cells(n_cells, rng)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    total = 0
    for start in range(0, len(cells), cells_per_batch):
        batch = _rows_for_cells(cells.iloc[start:start + cells_per_batch], rng, null_rate, mapping)
        batch.to_csv(output_path, mode="w" if start == 0 else "a", header=start == 0,
                     index=False, encoding="utf-8")
        total += len(batch)
    return total


def generate_fake_boards(cell_csv: Path, output_path: Path, n_boards: int = 60, seed: int = 7) -> int:
    """전처리된 cell.csv 주변에 가짜 게시대를 만들어 표준 board.csv로 저장한다.

    게시대 원본 형식은 아직 모르므로 전처리 없이 바로 표준 컬럼으로 만든다(데모용).
    같은 좌표 게시대 1쌍과 행정용 게시대를 포함한다.
    """
    rng = np.random.default_rng(seed)
    cells = pd.read_csv(cell_csv, dtype={"cell_id": str}).dropna(subset=["latitude", "longitude"])
    picks = cells.sample(n=n_boards, replace=n_boards > len(cells), random_state=seed).reset_index(drop=True)

    # Cell 중심에서 최대 ±110m 안쪽으로 흩뿌린다 (소속 Cell이 바뀌지 않을 정도).
    lat = picks["latitude"].to_numpy() + rng.uniform(-0.45, 0.45, n_boards) * LAT_STEP
    lng = picks["longitude"].to_numpy() + rng.uniform(-0.45, 0.45, n_boards) * LNG_STEP
    lat[1], lng[1] = lat[0], lng[0]  # 같은 좌표 게시대 1쌍

    purpose = rng.choice(["COMMERCIAL", "MIXED", "ADMINISTRATIVE"], size=n_boards, p=[0.55, 0.25, 0.20])
    fee = rng.choice([30000, 40000, 50000, 60000], size=n_boards).astype(float)
    fee[rng.random(n_boards) < 0.2] = np.nan  # 금액 미제공

    boards = pd.DataFrame({
        "board_id": np.arange(1, n_boards + 1),
        "name": [f"가상 게시대 {i:03d}" for i in range(1, n_boards + 1)],
        "city": picks["city_name"].to_numpy(),
        "dong": picks["dong_name"].to_numpy(),
        "address": [f"{c} {d} 가상로 {i}" for i, (c, d) in enumerate(zip(picks["city_name"], picks["dong_name"]), 1)],
        "latitude": np.round(lat, 6),
        "longitude": np.round(lng, 6),
        "purpose": purpose,
        "face_count": rng.integers(3, 11, n_boards),
        "posting_fee": pd.array(fee, dtype="Int64"),
        "data_reference_date": "2025-12-31",
    })
    output_path.parent.mkdir(parents=True, exist_ok=True)
    boards[BOARD_COLUMNS].to_csv(output_path, index=False, encoding="utf-8")
    return len(boards)
