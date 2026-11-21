"""추천 점수 참고 구현 (EDA·시뮬레이션용). 실제 서비스 점수는 Spring이 요청 시 계산한다.

backend/.../recommendation/ 의 Java 구현과 같은 규칙을 따르며, simulate.py가 산식·정규화 방식을
바꿔 가며 비교할 때 쓴다. 규칙이 바뀌면 두 곳을 함께 고친다.

1) 조건 → Cell별 값 (조회 API와 같은 UNKNOWN 규칙, backend PopulationFilter)
   N  = 전체 유동(체류+보행+주행)      : 기간·요일·시간대만 적용 (성별·연령 전체)
   T  = 타깃 유동(체류+보행+주행)      : N + 성별·연령
   Tf = 타깃의 선택 유동 유형(보행 등) : T와 같은 행에서 선택 유형 값만
2) Cell → 게시대 (3×3 가중 합, 팀 초안: 중심 0.8 + 주변 0.2)
   주변 8칸의 0.2는 게시대 위치에서 각 Cell 중심까지 거리(board_cell.distance_meters)에 따라
   가우스 감쇠 exp(-d²/2σ²), σ=250m(한 칸)로 나눈다. 데이터가 없는 칸은 빼고 남은 칸의 가중치 합이 1이 되게 맞춘다.
   → 결과는 '가중 평균 Cell 값'이라 3×3 합계(상세 화면 값)와 크기가 다르다. 점수 비교용 지수다.
3) 게시대 → 점수 (100점)
   A 유동 규모 40 (N), B 타깃 비율 40 (T/N), C 유형 비중 20 (Tf/T). 유동 유형 ALL이면 C를 빼고 50/50.
   지표 → 0~1 (시뮬레이션으로 정함, simulate.py):
     A: log(N)을 후보 안 최소~최대로 0~1 (최대 = 1, 범위는 최소 10배)
     B·C: 후보 전체 비율(ΣT/ΣN, ΣTf/ΣT) 대비 배수(리프트)를 log 척도로. 평균 = 0.5, 2배 이상 = 1, 절반 이하 = 0
   log로 더하므로 A+B는 대략 log(타깃 규모 T)를 보는 것과 같다 (T = N × T/N).
"""

from dataclasses import dataclass, field

import numpy as np
import pandas as pd

YEAR = 2025
AGE_GROUPS = ["UNDER_20", "AGE_20S", "AGE_30S", "AGE_40S", "AGE_50S", "AGE_60_PLUS"]
TIME_BANDS = ["06_08", "08_10", "10_12", "12_14", "14_16", "16_18", "18_20"]
SEASON_MONTHS = {"SPRING": [3, 4, 5], "SUMMER": [6, 7, 8], "AUTUMN": [9, 10, 11], "WINTER": [1, 2, 12]}
CITY_TO_DB = {"ANYANG": "안양시", "GUNPO": "군포시", "SIHEUNG": "시흥시"}

CENTER_WEIGHT = 0.8
NEIGHBOR_SIGMA_METERS = 250.0
# 초안 배점 (팀 공지 2026-11-11). ALL이면 C를 빼고 A·B를 같은 비율로 키운다.
POINTS = {"A": 40.0, "B": 40.0, "C": 20.0}
# 비율 지표(B·C)의 리프트 → 0~1: 후보 평균과 같으면 0.5, 2배 이상이면 1, 절반 이하면 0
LIFT_SPAN = 2.0
# 비율 지표를 후보 평균 쪽으로 당기는 세기 (0이면 끔). 시뮬레이션 E8
SHRINK = 0.5
# 규모 지표 log 척도의 최소 범위(배)
SIZE_MIN_SPAN = 10.0


@dataclass
class Criteria:
    cities: list[str] = field(default_factory=lambda: list(CITY_TO_DB))
    gender: str = "ALL"
    age_groups: list[str] = field(default_factory=lambda: list(AGE_GROUPS))
    flow_type: str = "ALL"
    time_bands: list[str] = field(default_factory=lambda: list(TIME_BANDS))
    period: str = "YEAR"            # YEAR / SPRING·SUMMER·AUTUMN·WINTER / 1~12월은 "M07" 형식
    day_type: str = "ALL"
    purposes: list[str] = field(default_factory=lambda: ["COMMERCIAL", "MIXED"])

    def months(self) -> list[str]:
        if self.period == "YEAR":
            ms = range(1, 13)
        elif self.period in SEASON_MONTHS:
            ms = SEASON_MONTHS[self.period]
        else:
            ms = [int(self.period[1:])]
        return [f"{YEAR}{m:02d}" for m in ms]


def _flow_sum(df: pd.DataFrame, cols: list[str]) -> pd.Series:
    """행별 값 합. 지정 컬럼이 모두 NULL이면 NULL (0과 구분)."""
    part = df[cols]
    return part.sum(axis=1, min_count=1)


def cell_values(fact: pd.DataFrame, c: Criteria) -> pd.DataFrame:
    """Cell별 N, T, Tf 합계 (선택 기간·요일 합. 일평균으로 나누지 않음 — 점수는 비율·순위라 영향 없음)."""
    f = fact[fact["base_month"].isin(c.months())]
    if c.day_type != "ALL":
        f = f[f["day_type"] == c.day_type]
    if len(c.time_bands) < len(TIME_BANDS):
        f = f[f["time_slot"].isin(c.time_bands)]
    total = _flow_sum(f, ["stay", "walk", "drive"])
    target_mask = pd.Series(True, index=f.index)
    if c.gender != "ALL":
        target_mask &= f["gender"] == c.gender
    if len(c.age_groups) < len(AGE_GROUPS):
        target_mask &= f["age_group"].isin(c.age_groups)
    tf_col = {"STAY": "stay", "WALK": "walk", "DRIVE": "drive"}.get(c.flow_type)
    tmp = pd.DataFrame({
        "cell_id": f["cell_id"],
        "N": total,
        "T": total.where(target_mask),
        "Tf": (f[tf_col] if tf_col else total).where(target_mask),
    })
    return tmp.groupby("cell_id").agg(lambda s: s.sum(min_count=1))


def neighbor_kernel(distance: np.ndarray, kernel: str) -> np.ndarray:
    if kernel == "gaussian":
        return np.exp(-(distance ** 2) / (2 * NEIGHBOR_SIGMA_METERS ** 2))
    if kernel == "inverse":
        return 1.0 / np.maximum(distance, 1.0)
    if kernel == "equal":
        return np.ones_like(distance)
    raise ValueError(kernel)


def board_inputs(board_cell: pd.DataFrame, cells: pd.DataFrame, kernel: str = "gaussian",
                 center_weight: float = CENTER_WEIGHT) -> pd.DataFrame:
    """게시대별 가중 N, T, Tf. kernel='sum'이면 가중치 없이 3×3 단순 합(현재 board_population과 같은 방식)."""
    bc = board_cell.join(cells, on="cell_id")
    bc = bc[bc[["N", "T", "Tf"]].notna().all(axis=1)].copy()
    if kernel == "sum":
        bc["w"] = 1.0
    else:
        bc["k"] = np.where(bc["is_center"], 0.0, neighbor_kernel(bc["distance_meters"].to_numpy(float), kernel))
        k_sum = bc.groupby("board_id")["k"].transform("sum")
        bc["w"] = np.where(bc["is_center"], center_weight,
                           np.where(k_sum > 0, (1 - center_weight) * bc["k"] / k_sum.replace(0, 1), 0.0))
        bc["w"] = bc["w"] / bc.groupby("board_id")["w"].transform("sum")
    for col in ["N", "T", "Tf"]:
        bc[col + "w"] = bc[col] * bc["w"]
    out = bc.groupby("board_id").agg(N=("Nw", "sum"), T=("Tw", "sum"), Tf=("Tfw", "sum"),
                                     cells=("cell_id", "count"))
    return out


def percentile(x: pd.Series) -> pd.Series:
    """후보 안 백분위 0~1 (동점은 평균 순위). 후보가 1개면 1."""
    if len(x) <= 1:
        return pd.Series(1.0, index=x.index)
    return (x.rank(method="average") - 1) / (len(x) - 1)


def minmax(x: pd.Series) -> pd.Series:
    span = x.max() - x.min()
    return pd.Series(1.0, index=x.index) if span == 0 else (x - x.min()) / span


def log_minmax(x: pd.Series, min_span: float = SIZE_MIN_SPAN) -> pd.Series:
    """규모 지표용: log 척도에서 후보 최소~최대를 0~1로(최대 = 1). 큰 쪽 꼬리의 차이가 백분위보다 덜 뭉개진다.

    범위는 최소 min_span배로 잡아, 후보끼리 비슷할 때(20 vs 18.8) 작은 차이를 0~1로 벌리지 않는다.
    """
    lx = np.log(x.clip(lower=1e-9))
    span = max(lx.max() - lx.min(), np.log(min_span))
    return 1 - (lx.max() - lx) / span


def lift_score(ratio: pd.Series, pooled: float, span: float = LIFT_SPAN) -> pd.Series:
    """후보 전체 비율(pooled) 대비 배수를 log 척도로 0~1에 놓는다. 같으면 0.5, span배면 1, 1/span배면 0."""
    lift = ratio / pooled if pooled > 0 else pd.Series(1.0, index=ratio.index)
    return (0.5 + np.log(lift.clip(lower=1e-9)) / (2 * np.log(span))).clip(0, 1)


def score(inputs: pd.DataFrame, flow_type: str, method: str = "hybrid", size_measure: str = "N",
          points: dict[str, float] | None = None, lift_span: float = LIFT_SPAN,
          shrink: float = SHRINK) -> pd.DataFrame:
    """inputs(N, T, Tf) → A·B·C 지표, 0~1 정규화 값, 배점, 총점.

    method: 'percentile' (모든 지표 백분위, 중간보고서 계획안) / 'minmax' / 'hybrid'(채택: A log 최소·최대 + B·C 리프트)
    size_measure: A 지표로 'N'(초안: 전체 유동) 또는 'T'(타깃 규모)
    """
    pts = dict(points or POINTS)
    df = inputs[inputs["N"] > 0].copy()
    df["A"] = df[size_measure]
    # 인구가 적은 게시대의 비율은 표본이 작아 튀므로 후보 전체 비율 쪽으로 당긴다 (경험적 베이즈).
    # k = shrink × 후보 중앙값: 중앙값 규모의 게시대는 비율 차이의 1/(1+shrink)만 반영된다.
    p_share = df["T"].sum() / df["N"].sum()
    p_flow = df["Tf"].sum() / df["T"].sum() if df["T"].sum() > 0 else 0.0
    k_n, k_t = shrink * df["N"].median(), shrink * df["T"].median()
    df["B"] = (df["T"] + k_n * p_share) / (df["N"] + k_n)
    df["C"] = np.where(df["T"] + k_t > 0, (df["Tf"] + k_t * p_flow) / (df["T"] + k_t).where(df["T"] + k_t > 0), 0.0)
    if flow_type == "ALL":
        scale = (pts["A"] + pts["B"] + pts["C"]) / (pts["A"] + pts["B"])
        pts = {"A": pts["A"] * scale, "B": pts["B"] * scale}
    pooled = {"B": df["T"].sum() / df["N"].sum(),
              "C": df["Tf"].sum() / df["T"].sum() if df["T"].sum() > 0 else 0.0}
    total = pd.Series(0.0, index=df.index)
    for key, weight in pts.items():
        if method == "percentile":
            norm = percentile(df[key])
        elif method == "minmax":
            norm = minmax(df[key])
        elif method == "hybrid":
            norm = log_minmax(df[key]) if key == "A" else lift_score(df[key], pooled[key], lift_span)
        else:
            raise ValueError(method)
        df[key + "_norm"] = norm
        df[key + "_pts"] = norm * weight
        total += df[key + "_pts"]
    df["score"] = total
    return df


def rank(scored: pd.DataFrame, boards: pd.DataFrame) -> pd.DataFrame:
    """명세 4.6 정렬: 점수↓ → 게시면수↓ → 부착금액↑(없음 뒤) → board_id↑"""
    df = scored.join(boards[["face_count", "posting_fee"]])
    df = df.assign(_face=df["face_count"].fillna(-1), _fee=df["posting_fee"].fillna(np.inf)).reset_index()
    df = df.sort_values(["score", "_face", "_fee", "board_id"], ascending=[False, False, True, True])
    return df.drop(columns=["_face", "_fee"]).set_index("board_id")


def candidates(boards: pd.DataFrame, c: Criteria) -> pd.Index:
    cities = [CITY_TO_DB[x] for x in c.cities]
    sel = boards[boards["city"].isin(cities) & boards["purpose"].isin(c.purposes)]
    return sel.index


def recommend(fact: pd.DataFrame, board_cell: pd.DataFrame, boards: pd.DataFrame, c: Criteria,
              method: str = "hybrid", kernel: str = "gaussian", size_measure: str = "N",
              cells: pd.DataFrame | None = None) -> pd.DataFrame:
    if cells is None:
        cells = cell_values(fact, c)
    bc = board_cell[board_cell["board_id"].isin(candidates(boards, c))]
    inputs = board_inputs(bc, cells, kernel)
    return rank(score(inputs, c.flow_type, method, size_measure), boards)
