"""게시대 추천 점수 계산 (provisional-v1).

API 명세 4.6의 계산안을 그대로 구현한다. 가중치·최소 타깃 인구 기준은 실데이터 EDA 후
확정 예정이므로(Q7) 여기서는 명세의 기본 가정값만 사용한다.

입력: 추천 조건으로 board_population을 집계한 게시대별 값
  T  = 타깃 인구 (조건에 맞는 성별·연령·시간대·기간)
  N  = 전체 인구 (같은 시간대·기간, 성별·연령 전체)
  Tf = 타깃 중 선택 유동 유형(STAY/WALK/DRIVE) 인구
점수: A=T, B=T/N, C=Tf/T, D=N 을 추천 대상 내 백분위로 바꿔 가중합, 0~100.

서비스 점수는 Spring이 요청 시 계산한다(명세 4.6, 확정). 이 모듈은 EDA·검증용 참고 구현이다.
"""

import pandas as pd

ALGORITHM_VERSION = "provisional-v1"

WEIGHTS = {"A": 0.4, "B": 0.3, "C": 0.2, "D": 0.1}


def weights_for(flow_type: str) -> dict[str, float]:
    """flowType=ALL이면 C(유형 적합도)를 빼고 나머지 가중치를 재정규화한다."""
    if flow_type == "ALL":
        rest = {k: w for k, w in WEIGHTS.items() if k != "C"}
        total = sum(rest.values())
        return {k: w / total for k, w in rest.items()}
    return dict(WEIGHTS)


def score_boards(df: pd.DataFrame, flow_type: str) -> pd.DataFrame:
    """게시대별 점수와 구성요소를 계산해 정렬된 DataFrame을 반환한다.

    필수 컬럼: board_id, T, N (flow_type != ALL이면 Tf도 필수)
    선택 컬럼: face_count, posting_fee (동점 정렬용)

    T 또는 N이 없거나(NaN) N이 0인 게시대, C를 쓰는데 Tf가 없는 게시대는
    점수를 매길 수 없으므로 제외한다.
    """
    weights = weights_for(flow_type)
    out = df.copy()
    out = out[out["T"].notna() & out["N"].notna() & (out["N"] > 0)]
    if "C" in weights:
        out = out[out["Tf"].notna()]

    out["A"] = out["T"]
    out["B"] = out["T"] / out["N"]
    out["D"] = out["N"]
    if "C" in weights:
        # 타깃이 0명이면 유형 비율을 정의할 수 없어 0으로 둔다.
        out["C"] = (out["Tf"] / out["T"]).where(out["T"] > 0, 0.0)

    out["score"] = 0.0
    for key, weight in weights.items():
        out[f"{key}_percentile"] = out[key].rank(pct=True, method="average")
        out[f"{key}_contribution"] = out[f"{key}_percentile"] * weight * 100
        out["score"] += out[f"{key}_contribution"]

    # 정렬: 점수 내림차순 → 게시면수 내림차순 → 부착금액 오름차순(없음 뒤) → board_id 오름차순
    sort_cols, ascending = ["score"], [False]
    if "face_count" in out.columns:
        sort_cols.append("face_count")
        ascending.append(False)
    if "posting_fee" in out.columns:
        sort_cols.append("posting_fee")
        ascending.append(True)
    sort_cols.append("board_id")
    ascending.append(True)

    out = out.sort_values(sort_cols, ascending=ascending, na_position="last")
    out["rank"] = range(1, len(out) + 1)
    out["display_score"] = out["score"].round().astype(int)
    return out.reset_index(drop=True)
