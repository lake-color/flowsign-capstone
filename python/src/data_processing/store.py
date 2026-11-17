"""상가 원본 CSV(소상공인시장진흥공단 상가(상권)정보, 시도별 파일) → 표준 store_source.csv 변환.

원본은 경기도 파일(UTF-8, BOM 포함)이고 이름은 data/raw/store_raw_<기준월 YYYYMM>.csv로 둔다.
대상 3개 시(안양시 만안구·동안구, 군포시, 시흥시)만 남기고, 업종을 화면용 묶음(category)으로 나눈다.
어느 Cell에 속하는지와 게시대 3×3 안에 드는지는 mapping.py가 정한다(store.csv).

업종 묶음 규칙 (원본 분류 이름 끝에 공백이 붙은 경우가 있어 이름이 아니라 코드로 판단한다):
  소분류 R10202(독서실/스터디 카페) → STUDY_ROOM      소분류 G21501(약국) → PHARMACY
  중분류 I212(비알코올)             → CAFE            중분류 I211(주점)   → BAR
  중분류 M111(수의)                 → VETERINARY
  나머지는 대분류 코드로 CATEGORY_BY_MAJOR. 표에 없는 대분류가 나오면 조용히 버리지 않고 오류를 낸다.
"""

import re
from pathlib import Path

import pandas as pd

from data_processing.board import TARGET_CITIES, TARGET_PROVINCE

STORE_RAW = re.compile(r"store_raw_(\d{6})\.csv")

# 표준 store_source.csv 컬럼 (store 테이블에서 cell_id만 빠진 것)
SOURCE_COLUMNS = ["store_id", "name", "category", "sub_category_name", "latitude", "longitude", "base_month"]

CATEGORY_BY_SUB = {"R10202": "STUDY_ROOM", "G21501": "PHARMACY"}
CATEGORY_BY_MIDDLE = {"I212": "CAFE", "I211": "BAR", "M111": "VETERINARY"}
CATEGORY_BY_MAJOR = {
    "I2": "RESTAURANT",
    "G2": "RETAIL",
    "S2": "LIVING_SERVICE",
    "P1": "EDUCATION",
    "Q1": "MEDICAL",
    "M1": "SCIENCE_TECH",
    "R1": "ARTS_SPORTS",
    "L1": "REAL_ESTATE",
    "N1": "FACILITY_RENTAL",
    "I1": "LODGING",
}
CATEGORIES = sorted({*CATEGORY_BY_SUB.values(), *CATEGORY_BY_MIDDLE.values(), *CATEGORY_BY_MAJOR.values()})

RAW_COLUMNS = ["상가업소번호", "상호명", "상권업종대분류코드", "상권업종중분류코드", "상권업종소분류코드",
               "상권업종소분류명", "시도명", "시군구명", "경도", "위도"]


def find_store_raw(raw_dir: Path) -> tuple[Path | None, list[str]]:
    """상가 원본 파일을 찾는다. (파일, 문제 목록)을 반환하고, 문제가 있으면 파일은 None이다."""
    found = sorted(raw_dir.glob("store_raw*")) if raw_dir.is_dir() else []
    matched = [p for p in found if STORE_RAW.fullmatch(p.name)]
    unexpected = [p.name for p in found if not STORE_RAW.fullmatch(p.name)]
    problems = []
    if not matched:
        problems.append(f"상가 원본이 없습니다: {raw_dir / 'store_raw_<기준월 YYYYMM>.csv'}")
    elif len(matched) > 1:
        problems.append(f"상가 원본이 여러 개입니다. 하나만 두세요: {[p.name for p in matched]}")
    if unexpected:
        problems.append(f"이름 규칙(store_raw_<YYYYMM>.csv)에 맞지 않는 파일이 있습니다: {unexpected}")
    return (matched[0] if not problems else None), problems


def _category(major: str, middle: str, sub: str) -> str | None:
    return CATEGORY_BY_SUB.get(sub) or CATEGORY_BY_MIDDLE.get(middle) or CATEGORY_BY_MAJOR.get(major)


def convert_store_csv(input_path: Path, output_path: Path, encoding: str = "utf-8-sig") -> dict:
    """원본 상가 CSV를 표준 store_source.csv로 저장하고 건수를 반환한다."""
    base_month = STORE_RAW.fullmatch(input_path.name).group(1)
    raw = pd.read_csv(input_path, encoding=encoding, usecols=RAW_COLUMNS, dtype=str, keep_default_na=False)
    # 안양시는 원본에 '안양시 만안구'·'안양시 동안구'처럼 구까지 붙어 있다.
    city = raw["시군구명"].str.split().str[0]
    raw = raw[(raw["시도명"] == TARGET_PROVINCE) & city.isin(TARGET_CITIES)].reset_index(drop=True)
    if raw.empty:
        raise ValueError("대상 3개 시의 상가가 없습니다.")

    category = [_category(a, b, c) for a, b, c in
                zip(raw["상권업종대분류코드"], raw["상권업종중분류코드"], raw["상권업종소분류코드"])]
    unknown = sorted(set(raw["상권업종대분류코드"][[c is None for c in category]]))
    if unknown:
        raise ValueError(f"업종 묶음에 없는 대분류 코드: {unknown}")
    if ((raw["위도"] == "") | (raw["경도"] == "")).any():
        raise ValueError("위경도가 없는 상가가 있습니다.")
    if raw["상가업소번호"].duplicated().any() or (raw["상가업소번호"] == "").any():
        raise ValueError("상가업소번호가 비었거나 중복됩니다.")

    stores = pd.DataFrame({
        "store_id": raw["상가업소번호"],
        "name": raw["상호명"].str.strip().replace("", None),
        "category": category,
        "sub_category_name": raw["상권업종소분류명"].str.strip(),
        "latitude": raw["위도"].astype(float),
        "longitude": raw["경도"].astype(float),
        "base_month": base_month,
    })
    output_path.parent.mkdir(parents=True, exist_ok=True)
    stores[SOURCE_COLUMNS].to_csv(output_path, index=False, encoding="utf-8")
    by_city = raw["시군구명"].str.split().str[0].value_counts()
    return {"stores": len(stores), "base_month": base_month, "by_city": by_city.to_dict()}
