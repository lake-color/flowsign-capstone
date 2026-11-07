"""유동인구 원본 CSV 전처리.

원본 CSV를 청크 단위로 읽어 표준 컬럼명/코드로 정제한 뒤 출력 폴더에 저장한다.
  population_fact.csv    = Cell × 월 × day_type × 성별 × 연령 × 시간대별 6종 인구
  cell.csv               = Cell ID / 중심 위경도 / 시 이름 / 동 이름 (Cell당 1행)
  preprocess_report.json = 행 수·NULL 수·UNKNOWN 수 등 품질 집계 (원본 행은 담지 않음)
게시대 영향권 매핑·집계는 mapping.py, DB 적재는 psql의 copy 명령이 담당한다.

원본 컬럼명과 코드값은 코드에 두지 않고 매핑 파일(raw_mapping.json)로 읽는다.
실제 원본용 매핑 파일은 저장소에 올리지 않으며, 공개 저장소에는 가짜 데이터 형식의
raw_mapping.example.json만 있다. 원본 형식이 바뀌면 매핑 파일만 수정한다.

NULL 처리 (어느 컬럼이든 결측이 있을 수 있다고 본다):
  - 분류 컬럼(주중구분·성별·연령·시간대) NULL → 행을 버리지 않고 UNKNOWN으로 보존
  - cell_id / base_month NULL → 어디에도 둘 수 없으므로 행을 버리고 리포트에 센다
  - 인구값 NULL → NULL 그대로 (0으로 채우지 않음)
  - 위경도·지역명 NULL → 같은 Cell의 다른 행 값을 쓰고, 끝까지 없으면 빈 값으로 두고 리포트에 센다
형식 자체가 다른 값(매핑표에 없는 코드, 숫자가 아닌 인구값, 6자리가 아닌 연월)은
원본 확인이 덜 됐다는 신호이므로 조용히 넘기지 않고 오류를 낸다.
"""

import json
from pathlib import Path

import pandas as pd

KEY_COLUMNS = ["cell_id", "base_month", "day_type", "gender", "age_group", "time_slot"]
POPULATION_COLUMNS = ["stay", "walk", "drive", "residence", "work", "visit"]
CELL_COLUMNS = ["cell_id", "latitude", "longitude", "city_name", "dong_name"]
REQUIRED_KEY_COLUMNS = ["cell_id", "base_month"]
STANDARD_COLUMNS = list(dict.fromkeys(KEY_COLUMNS + CELL_COLUMNS + POPULATION_COLUMNS))
CATEGORY_COLUMNS = ["day_type", "gender", "age_group", "time_slot"]

# 원본 컬럼명·코드값 매핑 파일. 실제 원본용 raw_mapping.json은 커밋하지 않고(.gitignore)
# 로컬에만 둔다. 없으면 공개용 예시(가짜 데이터 형식)를 쓴다.
PYTHON_DIR = Path(__file__).resolve().parents[2]
LOCAL_MAPPING = PYTHON_DIR / "raw_mapping.json"
EXAMPLE_MAPPING = PYTHON_DIR / "raw_mapping.example.json"


class RawMapping:
    """원본 컬럼명 ↔ 표준 컬럼명, 원본 코드 → 표준 코드 대응."""

    def __init__(self, columns: dict[str, str], codes: dict[str, dict[str, str]]) -> None:
        # columns: 표준 컬럼명 → 원본 컬럼명, codes: 분류 컬럼 → {원본 코드: 표준 코드}
        unknown = [c for c in columns if c not in STANDARD_COLUMNS]
        if unknown:
            raise ValueError(f"매핑 파일의 columns에 표준이 아닌 컬럼: {unknown}")
        missing = [c for c in CATEGORY_COLUMNS if c not in codes and c != "day_type"]
        if missing:
            raise ValueError(f"매핑 파일의 codes에 누락된 분류: {missing}")
        self.columns = columns
        self.codes = codes

    @property
    def rename(self) -> dict[str, str]:
        """원본 컬럼명 → 표준 컬럼명."""
        return {raw: std for std, raw in self.columns.items()}


def load_mapping(path: Path | None = None) -> RawMapping:
    """매핑 파일을 읽는다. path가 없으면 로컬 raw_mapping.json, 그것도 없으면 예시 파일."""
    if path is None:
        path = LOCAL_MAPPING if LOCAL_MAPPING.exists() else EXAMPLE_MAPPING
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return RawMapping(data["columns"], data["codes"])


# 분류 컬럼 NULL을 보존할 때 쓰는 값. 예: 성별 '전체' 집계에는 포함, 남/여 선택 시 제외.
UNKNOWN = "UNKNOWN"

# 원본에 주중/주말 컬럼 자체가 없으면 추정하지 않고 이 값으로 둔다.
DAY_TYPE_UNSPECIFIED = "UNSPECIFIED"

# 원본에서 NULL로 볼 표기. 실제 파일의 NULL 표기가 다르면 여기에 추가한다.
NULL_TOKENS = ["", "NULL", "null", "Null", "\\N"]

BASE_MONTH_PATTERN = r"^\d{6}$"


class QualityReport:
    """청크마다 센 값을 합쳐 preprocess_report.json으로 남긴다. 원본 행 내용은 담지 않는다."""

    def __init__(self) -> None:
        self.rows_read = 0
        self.rows_written = 0
        self.dropped: dict[str, int] = {f"{c}_null": 0 for c in REQUIRED_KEY_COLUMNS}
        self.null_counts: dict[str, int] = {}
        self.unknown_counts: dict[str, int] = {c: 0 for c in CATEGORY_COLUMNS}
        self.negative_counts: dict[str, int] = {c: 0 for c in POPULATION_COLUMNS}
        self.base_months: set[str] = set()

    def add_nulls(self, df: pd.DataFrame) -> None:
        for col, n in df.isna().sum().items():
            self.null_counts[col] = self.null_counts.get(col, 0) + int(n)

    def to_dict(self, cells: pd.DataFrame, cells_with_multiple_names: int) -> dict:
        coords = cells.dropna(subset=["latitude", "longitude"])
        return {
            "rows_read": self.rows_read,
            "rows_written": self.rows_written,
            "rows_dropped": self.dropped,
            "null_counts_raw": self.null_counts,
            "unknown_counts": self.unknown_counts,
            "negative_value_counts": self.negative_counts,
            "base_months": sorted(self.base_months),
            "cells": len(cells),
            "cells_without_coordinates": int(len(cells) - len(coords)),
            "cells_with_multiple_names": cells_with_multiple_names,
            "bbox": None if coords.empty else {
                "min_latitude": float(coords["latitude"].min()),
                "max_latitude": float(coords["latitude"].max()),
                "min_longitude": float(coords["longitude"].min()),
                "max_longitude": float(coords["longitude"].max()),
            },
        }


def _map_codes(df: pd.DataFrame, col: str, code_map: dict[str, str]) -> int:
    """col을 code_map으로 바꾸고 NULL은 UNKNOWN으로 둔다. UNKNOWN으로 둔 행 수를 반환한다."""
    source = df[col].astype("string").str.strip()
    mapped = source.map(code_map)
    unknown = source[source.notna() & mapped.isna()].unique()
    if len(unknown) > 0:
        raise ValueError(f"{col}에 매핑되지 않은 코드: {list(unknown)[:10]}")
    nulls = source.isna()
    df[col] = mapped.where(~nulls, UNKNOWN)
    return int(nulls.sum())


def _to_number(df: pd.DataFrame, col: str) -> None:
    """숫자로 바꾼다. NULL은 그대로 두고, 숫자가 아닌 값이 있으면 오류를 낸다."""
    source = df[col].astype("string").str.strip()
    numbers = pd.to_numeric(source, errors="coerce")
    bad = source[source.notna() & numbers.isna()].unique()
    if len(bad) > 0:
        raise ValueError(f"{col}에 숫자가 아닌 값: {list(bad)[:10]}")
    df[col] = numbers


def clean_chunk(
    raw: pd.DataFrame, mapping: RawMapping, report: QualityReport | None = None
) -> pd.DataFrame:
    """원본 청크 하나를 표준 형태(KEY + CELL 속성 + 인구 6종)로 정제한다."""
    report = report or QualityReport()
    df = raw.rename(columns=mapping.rename)

    if "day_type" not in df.columns:
        df["day_type"] = DAY_TYPE_UNSPECIFIED

    columns = STANDARD_COLUMNS
    missing_cols = [c for c in columns if c not in df.columns]
    if missing_cols:
        raise ValueError(f"표준 컬럼 누락: {missing_cols}")

    df = df[columns].copy()
    for col in ["cell_id", "base_month", "city_name", "dong_name"]:
        df[col] = df[col].astype("string").str.strip()
    report.rows_read += len(df)
    report.add_nulls(df)

    for col in REQUIRED_KEY_COLUMNS:
        nulls = df[col].isna()
        report.dropped[f"{col}_null"] += int(nulls.sum())
        df = df[~nulls]

    bad_months = df["base_month"][~df["base_month"].str.match(BASE_MONTH_PATTERN)].unique()
    if len(bad_months) > 0:
        raise ValueError(f"base_month 형식(YYYYMM)이 아닌 값: {list(bad_months)[:10]}")
    report.base_months.update(df["base_month"].unique())

    for col in CATEGORY_COLUMNS:
        if col == "day_type" and (df[col] == DAY_TYPE_UNSPECIFIED).all():
            continue
        report.unknown_counts[col] += _map_codes(df, col, mapping.codes[col])

    for col in POPULATION_COLUMNS + ["latitude", "longitude"]:
        _to_number(df, col)
    for col in POPULATION_COLUMNS:
        report.negative_counts[col] += int((df[col] < 0).sum())

    report.rows_written += len(df)
    return df


def _resolve_cells(cell_rows: pd.DataFrame) -> tuple[pd.DataFrame, int]:
    """Cell당 1행으로 줄인다. 좌표가 서로 다르면 오류, 지역명이 여러 개면 가장 많은 값을 쓴다."""
    coords = cell_rows.dropna(subset=["latitude", "longitude"]).drop_duplicates(["cell_id", "latitude", "longitude"])
    conflicts = coords["cell_id"][coords["cell_id"].duplicated()].unique()
    if len(conflicts) > 0:
        raise ValueError(f"같은 cell_id에 좌표가 여러 개: {list(conflicts)[:10]}")

    names = cell_rows.dropna(subset=["city_name", "dong_name"])
    multi = int((names.groupby("cell_id")[["city_name", "dong_name"]].nunique() > 1).any(axis=1).sum())
    names = (
        names.groupby(["cell_id", "city_name", "dong_name"]).size().reset_index(name="n")
        .sort_values(["cell_id", "n"], ascending=[True, False])
        .drop_duplicates("cell_id")[["cell_id", "city_name", "dong_name"]]
    )

    cells = pd.DataFrame({"cell_id": cell_rows["cell_id"].unique()})
    cells = cells.merge(coords[["cell_id", "latitude", "longitude"]], on="cell_id", how="left")
    cells = cells.merge(names, on="cell_id", how="left")
    return cells[CELL_COLUMNS].sort_values("cell_id").reset_index(drop=True), multi


def preprocess_csv(
    input_path: Path, output_dir: Path, chunksize: int = 1_000_000, encoding: str = "utf-8",
    mapping_path: Path | None = None,
) -> dict:
    """대용량 원본 CSV를 청크로 정제해 output_dir에 표준 CSV 2개와 품질 리포트를 저장한다.

    품질 리포트 내용(dict)을 반환한다.
    """
    mapping = load_mapping(mapping_path)
    output_dir.mkdir(parents=True, exist_ok=True)
    fact_path = output_dir / "population_fact.csv"
    report = QualityReport()

    first = True
    cell_parts: list[pd.DataFrame] = []
    with pd.read_csv(
        input_path, chunksize=chunksize, dtype=str, encoding=encoding,
        keep_default_na=False, na_values=NULL_TOKENS,
    ) as reader:
        for raw in reader:
            cleaned = clean_chunk(raw, mapping, report)
            cleaned[KEY_COLUMNS + POPULATION_COLUMNS].to_csv(
                fact_path, mode="w" if first else "a", header=first, index=False
            )
            first = False
            # Cell 속성은 청크마다 중복을 줄여 모아 둔다 (Cell 수는 수천 개 수준).
            cell_parts.append(cleaned[CELL_COLUMNS].drop_duplicates())

    if first:
        raise ValueError("원본에 데이터 행이 없습니다.")

    cells, multi_names = _resolve_cells(pd.concat(cell_parts).drop_duplicates())
    cells.to_csv(output_dir / "cell.csv", index=False)

    result = report.to_dict(cells, multi_names)
    (output_dir / "preprocess_report.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return result
