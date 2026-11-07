"""게시대 ↔ Cell 매핑과 게시대 영향권 집계.

API 명세 1장·1.2 기준. 입력은 전처리기가 만든 표준 CSV(cell, population_fact)와
표준 board.csv이므로 원본 형식이 바뀌어도 이 모듈은 수정하지 않는다.

출력 표준 CSV (psql copy로 적재, 테이블은 Flyway migration이 생성):
  board_cell.csv       = 게시대별 소속 Cell + 주변 8칸 중 데이터에 있는 Cell (최대 9행)
  board_population.csv = 게시대 3×3 Cell 합계 × 월 × day_type × 성별 × 연령 × 시간대
  grid_spec.csv        = 지도 격자선 기준값 1행 (원점·위경도 간격·검증 상태)

규칙:
  - Cell은 250m, 중심 ±125m 정사각형. 좌표는 WGS84 위경도 그대로(pyproj·PostGIS 미사용),
    거리는 짧은 거리용 평면 근사식으로 계산한다.
  - 소속 Cell = 게시대 위경도에서 가장 가까운 Cell 중심 (거리 제한 없음).
  - 3×3 = 소속 Cell 중심에서 상하좌우(250m)·대각선(약 354m) 이웃. 다음 바깥 칸은 500m부터이므로
    그 사이 값(NEIGHBOR_MAX_METERS)으로 자른다.
  - 원본에 없는 칸은 board_cell에 넣지 않는다(집계에서 빠짐). 9칸 모두 없을 수는 없다(소속 Cell은 항상 있음).
"""

from pathlib import Path

import numpy as np
import pandas as pd

CELL_SIZE_METERS = 250
METERS_PER_DEG_LAT = 111_320.0
NEIGHBOR_MAX_METERS = 430.0
# Cell 중심이 격자 위에 있는지 볼 때 허용하는 오차 (격자 간격 대비 비율). 실데이터 확인 후 확정.
GRID_TOLERANCE = 0.05

KEY_COLUMNS = ["base_month", "day_type", "gender", "age_group", "time_slot"]
POPULATION_COLUMNS = ["stay", "walk", "drive", "residence", "work", "visit"]


def _distance_meters(lat1, lng1, lat2, lng2):
    """짧은 거리용 평면 근사 거리(m). 수 km 이내에서 오차가 무시할 수준이다."""
    mean_lat = np.radians((np.asarray(lat1) + np.asarray(lat2)) / 2)
    dy = (np.asarray(lat2) - np.asarray(lat1)) * METERS_PER_DEG_LAT
    dx = (np.asarray(lng2) - np.asarray(lng1)) * METERS_PER_DEG_LAT * np.cos(mean_lat)
    return np.hypot(dx, dy)


def map_boards_to_cells(boards: pd.DataFrame, cells: pd.DataFrame) -> pd.DataFrame:
    """게시대마다 소속 Cell과 3×3 이웃 Cell을 찾아 board_cell 행을 만든다."""
    cells = cells.dropna(subset=["latitude", "longitude"]).reset_index(drop=True)
    c_lat = cells["latitude"].to_numpy()
    c_lng = cells["longitude"].to_numpy()

    rows = []
    for board in boards.itertuples(index=False):
        to_board = _distance_meters(board.latitude, board.longitude, c_lat, c_lng)
        center = int(np.argmin(to_board))
        to_center = _distance_meters(c_lat[center], c_lng[center], c_lat, c_lng)
        for i in np.flatnonzero(to_center <= NEIGHBOR_MAX_METERS):
            rows.append((board.board_id, cells.at[i, "cell_id"], i == center, round(float(to_board[i]), 1)))
    return pd.DataFrame(rows, columns=["board_id", "cell_id", "is_center", "distance_meters"])


def aggregate_board_population(population_fact_csv: Path, board_cell: pd.DataFrame,
                               chunksize: int = 1_000_000) -> pd.DataFrame:
    """population_fact를 청크로 읽어 게시대 3×3 Cell 합계를 만든다.

    NULL만 있는 칸의 합은 0이 아니라 NULL로 둔다. cell_count는 값을 보탠 Cell 수다.
    """
    links = board_cell[["board_id", "cell_id"]]
    wanted = set(links["cell_id"])
    parts = []
    with pd.read_csv(population_fact_csv, chunksize=chunksize, dtype={"cell_id": str, "base_month": str}) as reader:
        for chunk in reader:
            chunk = chunk[chunk["cell_id"].isin(wanted)]
            if chunk.empty:
                continue
            # UNKNOWN 행은 같은 키가 여러 번 나올 수 있으므로 Cell 단위로 먼저 합친다.
            parts.append(chunk.groupby(["cell_id"] + KEY_COLUMNS, as_index=False)[POPULATION_COLUMNS].sum(min_count=1))

    if not parts:
        return pd.DataFrame(columns=["board_id"] + KEY_COLUMNS + ["cell_count"] + POPULATION_COLUMNS)

    per_cell = pd.concat(parts).groupby(["cell_id"] + KEY_COLUMNS, as_index=False)[POPULATION_COLUMNS].sum(min_count=1)
    merged = links.merge(per_cell, on="cell_id")
    grouped = merged.groupby(["board_id"] + KEY_COLUMNS)
    result = grouped[POPULATION_COLUMNS].sum(min_count=1).round(4)  # 부동소수점 합산 잡음 제거
    result.insert(0, "cell_count", grouped["cell_id"].nunique())
    return result.reset_index()[["board_id"] + KEY_COLUMNS + ["cell_count"] + POPULATION_COLUMNS]


def compute_grid_spec(cells: pd.DataFrame) -> dict:
    """Cell 중심 좌표에서 격자 원점(남서쪽 모서리)과 간격을 구하고 모든 중심이 격자 위에 있는지 검증한다."""
    cells = cells.dropna(subset=["latitude", "longitude"])

    def step_and_origin(values: np.ndarray) -> tuple[float, float]:
        unique = np.unique(np.round(values, 7))
        diffs = np.diff(unique)
        step = float(np.min(diffs[diffs > 1e-6])) if len(diffs) else 0.0
        return step, float(unique.min() - step / 2)

    lat_step, origin_lat = step_and_origin(cells["latitude"].to_numpy())
    lng_step, origin_lng = step_and_origin(cells["longitude"].to_numpy())

    def on_grid(values: np.ndarray, origin: float, step: float) -> bool:
        if step <= 0:
            return False
        pos = (values - origin) / step - 0.5
        return bool(np.all(np.abs(pos - np.round(pos)) <= GRID_TOLERANCE))

    verified = (on_grid(cells["latitude"].to_numpy(), origin_lat, lat_step)
                and on_grid(cells["longitude"].to_numpy(), origin_lng, lng_step))
    return {
        "id": 1,
        "cell_size_meters": CELL_SIZE_METERS,
        "origin_latitude": round(origin_lat, 7),
        "origin_longitude": round(origin_lng, 7),
        "latitude_step_deg": round(lat_step, 7),
        "longitude_step_deg": round(lng_step, 7),
        "status": "VERIFIED" if verified else "NOT_VERIFIED",
    }


def run_mapping(processed_dir: Path, board_csv: Path) -> dict:
    """processed_dir의 cell.csv, population_fact.csv와 board.csv로 매핑 결과 CSV 3개를 만든다."""
    cells = pd.read_csv(processed_dir / "cell.csv", dtype={"cell_id": str})
    boards = pd.read_csv(board_csv)

    board_cell = map_boards_to_cells(boards, cells)
    board_cell.to_csv(processed_dir / "board_cell.csv", index=False)

    board_population = aggregate_board_population(processed_dir / "population_fact.csv", board_cell)
    board_population.to_csv(processed_dir / "board_population.csv", index=False)

    grid = compute_grid_spec(cells)
    pd.DataFrame([grid]).to_csv(processed_dir / "grid_spec.csv", index=False)

    return {
        "boards": len(boards),
        "board_cell_rows": len(board_cell),
        "boards_with_9_cells": int((board_cell.groupby("board_id").size() == 9).sum()),
        "board_population_rows": len(board_population),
        "grid_status": grid["status"],
    }
