"""게시대 원본 CSV(공공데이터포털 전국 현수막게시대 표준데이터) → 표준 board.csv 변환.

원본은 전국 데이터이고 CP949 인코딩이다. 대상 3개 시(경기도 안양시·군포시·시흥시)만 남긴다.
원본에 위경도가 있으므로 지오코딩은 하지 않는다.

변환 규칙:
  - board_id            = 대상 시 행에 원본 순서대로 1부터 붙인 순번 (원본에 고유 ID가 없음)
  - name                = 게시대위치명 (게시대명은 "E-020"처럼 관리 코드라 쓰지 않음)
  - dong                = 소재지지번주소의 동·읍·면. 지번주소가 없으면 NULL
  - address             = 소재지지번주소, 없으면 소재지도로명주소
  - purpose             = 상업용 → COMMERCIAL, 행정용 → ADMINISTRATIVE, 상업용+행정용 → MIXED
  - posting_fee         = 부착금액(원). "무료"는 0, 빈 값은 NULL(금액 미제공)
  - 현수막규격·관리기관·제공기관 등 board 테이블에 없는 컬럼은 버린다.
예상하지 못한 용도·금액 값이나 위경도가 없는 행은 조용히 버리지 않고 오류를 낸다.
"""

import re
from pathlib import Path

import pandas as pd

# 표준 board.csv 컬럼 (board 테이블과 같은 순서)
BOARD_COLUMNS = ["board_id", "name", "city", "dong", "address", "latitude", "longitude",
                 "purpose", "face_count", "posting_fee", "data_reference_date"]
TARGET_PROVINCE = "경기도"
TARGET_CITIES = ["안양시", "군포시", "시흥시"]
PURPOSE_CODES = {"상업용": "COMMERCIAL", "행정용": "ADMINISTRATIVE", "상업용+행정용": "MIXED"}
FREE_FEE = "무료"


def _dong(lot_address: str) -> str | None:
    """'경기도 안양시 만안구 석수동 283-2' → '석수동'."""
    for token in lot_address.split():
        if re.fullmatch(r"\S+[동읍면]", token) and not token.endswith(("시", "구", "군")):
            return token
    return None


def _fee(value: str) -> int | None:
    value = value.strip().replace(",", "")
    if value == "":
        return None
    if value == FREE_FEE:
        return 0
    if not value.isdigit():
        raise ValueError(f"부착금액을 해석할 수 없습니다: {value!r}")
    return int(value)


def convert_board_csv(input_path: Path, output_path: Path, encoding: str = "cp949") -> dict:
    """원본 게시대 CSV를 표준 board.csv로 저장하고 시별 건수를 반환한다."""
    raw = pd.read_csv(input_path, encoding=encoding, dtype=str, keep_default_na=False)
    raw = raw[(raw["시도명"] == TARGET_PROVINCE) & raw["시군구명"].isin(TARGET_CITIES)].reset_index(drop=True)
    if raw.empty:
        raise ValueError("대상 3개 시의 게시대가 없습니다.")

    unknown_purpose = sorted(set(raw["용도구분명"]) - PURPOSE_CODES.keys())
    if unknown_purpose:
        raise ValueError(f"알 수 없는 용도구분명: {unknown_purpose}")
    if ((raw["위도"] == "") | (raw["경도"] == "")).any():
        raise ValueError("위경도가 없는 게시대가 있습니다.")

    lot = raw["소재지지번주소"].str.strip()
    road = raw["소재지도로명주소"].str.strip()
    boards = pd.DataFrame({
        "board_id": range(1, len(raw) + 1),
        "name": raw["게시대위치명"].str.strip(),
        "city": raw["시군구명"],
        "dong": lot.map(_dong),
        "address": lot.where(lot != "", road).replace("", None),
        "latitude": raw["위도"].astype(float),
        "longitude": raw["경도"].astype(float),
        "purpose": raw["용도구분명"].map(PURPOSE_CODES),
        "face_count": pd.to_numeric(raw["게시면수"].replace("", None)).astype("Int64"),
        "posting_fee": pd.array(raw["부착금액"].map(_fee), dtype="Int64"),
        "data_reference_date": raw["데이터기준일자"].replace("", None),
    })
    output_path.parent.mkdir(parents=True, exist_ok=True)
    boards[BOARD_COLUMNS].to_csv(output_path, index=False, encoding="utf-8")
    return {
        "boards": len(boards),
        "by_city": boards["city"].value_counts().to_dict(),
        "dong_missing": int(boards["dong"].isna().sum()),
        "fee_missing": int(boards["posting_fee"].isna().sum()),
    }
