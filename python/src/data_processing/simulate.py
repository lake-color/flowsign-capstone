"""추천 점수 시뮬레이션: 시나리오 2개로 산식·정규화·인접 Cell 가중치를 비교한다.

실행 (python 폴더):  uv run python -m data_processing.simulate fake      # 또는 real
입력: data/generated_<모드>/의 population_fact.csv, board_cell.csv, board.csv (DB 불필요)
출력: data/generated_<모드>/simulation/report.md  (data/ 아래라 Git에 올라가지 않는다)

※ real 결과는 실제 데이터에서 나온 집계이므로 README '데이터와 보안' 규칙대로 외부에 올리지 않는다.

실험
  E1 노이즈 검사   : 지표별 배점이 순위를 얼마나 흔드는지(배점 표준편차). 가짜 데이터는 Cell마다 성별·연령·유형
                     비중이 같게 만들어져 B·C에 진짜 차이가 없으므로, B·C 배점이 크게 벌어지면 노이즈를 키운다는 뜻
  E2 신호 심기     : 후보 게시대 일부의 중심 Cell에 타깃 인구를 +50% 심고, 그 게시대 순위가 얼마나 오르는지
                     (광고주 타깃 조건이 결과에 반영되는지 — 교수님 피드백 1)
  E3 시기 안정성   : 같은 타깃으로 계절만 바꿨을 때 TOP 5가 얼마나 유지되는지 (시기에 따라 값이 튀는지)
  E4 인접 가중치   : 3×3 단순 합 / 주변 균등 / 거리 반비례 / 거리 가우스 비교
  E5 리프트 범위   : 비율 지표 만점 배수(span)별 노이즈·신호 반응·안정성
  E6 PRD N16       : 대표 조건 10개에서 추천 TOP 10의 타깃 인구(점수와 같은 가중 합) 합이 '전체 유동 많은 순 10개'보다 큰지
  E7 PRD N17       : 대표 조건 5개에서 배점을 ±10점 바꿔도 TOP 10이 평균 7개 이상 유지되는지
  E8 비율 보정     : 인구가 적은 게시대의 비율을 평균 쪽으로 당기는 세기(shrink)별 N16 최저치·신호 반응·안정성
"""

import sys
from itertools import combinations
from pathlib import Path

import numpy as np
import pandas as pd

from data_processing.scoring import (
    Criteria,
    board_inputs,
    candidates,
    cell_values,
    neighbor_kernel,
    rank,
    recommend,
    score,
)

DATA_DIR = Path(__file__).resolve().parents[3] / "data"
TOP = 5
METHODS = ["percentile", "minmax", "hybrid"]

SCENARIOS = {
    "S1 안양 2030 여성 보행 (카페·뷰티 오픈 홍보)": Criteria(
        cities=["ANYANG"], gender="FEMALE", age_groups=["AGE_20S", "AGE_30S"], flow_type="WALK",
        time_bands=["14_16", "16_18", "18_20"], period="SUMMER", day_type="WEEKEND"),
    "S2 3개 시 3050 남성 주행 (자동차·보험 출퇴근 홍보)": Criteria(
        gender="MALE", age_groups=["AGE_30S", "AGE_40S", "AGE_50S"], flow_type="DRIVE",
        time_bands=["06_08", "08_10", "18_20"], period="YEAR", day_type="WEEKDAY"),
}


# PRD N16·N17 점검용 대표 조건 (시나리오 2개 + 서로 다른 지역·타깃·유형·시간·기간)
REPRESENTATIVE = [*SCENARIOS.values(),
    Criteria(gender="FEMALE", age_groups=["AGE_20S"]),
    Criteria(cities=["SIHEUNG"], gender="MALE", age_groups=["AGE_60_PLUS"], flow_type="WALK",
             time_bands=["06_08", "08_10"]),
    Criteria(cities=["GUNPO"], age_groups=["UNDER_20"], flow_type="WALK", time_bands=["14_16", "16_18"],
             day_type="WEEKDAY"),
    Criteria(cities=["ANYANG"], age_groups=["AGE_30S", "AGE_40S"], flow_type="STAY", day_type="WEEKEND"),
    Criteria(gender="FEMALE", age_groups=["AGE_40S", "AGE_50S"], flow_type="WALK", time_bands=["10_12", "12_14"],
             day_type="WEEKDAY"),
    Criteria(cities=["SIHEUNG"], gender="MALE", age_groups=["AGE_20S"], flow_type="DRIVE", time_bands=["18_20"],
             day_type="WEEKEND", period="M07"),
    Criteria(),
    Criteria(cities=["GUNPO"], gender="FEMALE", age_groups=["AGE_60_PLUS"], flow_type="STAY", period="WINTER"),
]


def load_set(mode: str):
    d = DATA_DIR / {"fake": "generated_fake", "real": "generated_real"}[mode]
    fact = pd.read_csv(d / "population_fact.csv", dtype={"cell_id": str, "base_month": str})
    board_cell = pd.read_csv(d / "board_cell.csv", dtype={"cell_id": str})
    board_cell["is_center"] = board_cell["is_center"].astype(str).str.lower() == "true"
    boards = pd.read_csv(d / "board.csv").set_index("board_id")
    return d, fact, board_cell, boards


def top(ranked: pd.DataFrame, n: int = TOP) -> list[int]:
    return list(ranked.index[:n])


def spearman(a: pd.DataFrame, b: pd.DataFrame) -> float:
    common = a.index.intersection(b.index)
    ra = pd.Series(range(len(a)), index=a.index)[common]
    rb = pd.Series(range(len(b)), index=b.index)[common]
    # 값이 이미 순위라 Pearson = Spearman (scipy 의존성 없이)
    return float(np.corrcoef(ra.rank(), rb.rank())[0, 1])


def jaccard(x: list, y: list) -> float:
    return len(set(x) & set(y)) / len(set(x) | set(y))


def md_table(rows: list[dict]) -> str:
    cols = list(rows[0])
    out = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    out += ["| " + " | ".join(str(r[c]) for c in cols) + " |" for r in rows]
    return "\n".join(out)


def e1_noise(fact, board_cell, boards, c) -> list[dict]:
    cells = cell_values(fact, c)
    inputs = board_inputs(board_cell[board_cell["board_id"].isin(candidates(boards, c))], cells)
    rows = []
    for size in ["N", "T"]:
        for m in METHODS:
            s = score(inputs, c.flow_type, m, size)
            row = {"A 지표": "전체 유동 N" if size == "N" else "타깃 규모 T", "정규화": m}
            for k in ["A", "B", "C"]:
                row[f"{k} 배점 표준편차"] = f"{s[k + '_pts'].std():.1f}" if k + "_pts" in s else "-"
            row["B 원값 변동계수"] = f"{s['B'].std() / s['B'].mean():.3f}"
            rows.append(row)
    return rows


def plant(cells: pd.DataFrame, bc: pd.DataFrame, seed: int = 7, k: int = 10, boost: float = 0.5):
    """후보 k곳의 중심 Cell에 타깃 인구를 boost만큼 더한 Cell 값 (전체 인구도 같은 양만큼 늘어난다)."""
    rng = np.random.default_rng(seed)
    planted = rng.choice(np.array(sorted(bc["board_id"].unique())), size=k, replace=False)
    planted_cells = bc[bc["board_id"].isin(planted) & bc["is_center"]]["cell_id"].unique()
    boosted = cells.copy()
    extra_t = boosted.loc[planted_cells, "T"] * boost
    boosted.loc[planted_cells, "N"] += extra_t
    boosted.loc[planted_cells, "Tf"] += boosted.loc[planted_cells, "Tf"] * boost
    boosted.loc[planted_cells, "T"] += extra_t
    return planted, boosted


def position(ranked: pd.DataFrame, ids) -> pd.Series:
    return pd.Series(range(1, len(ranked) + 1), index=ranked.index)[ids]


def e2_planted(fact, board_cell, boards, c) -> list[dict]:
    cells = cell_values(fact, c)
    bc = board_cell[board_cell["board_id"].isin(candidates(boards, c))]
    planted, boosted = plant(cells, bc)
    rows = []
    for size in ["N", "T"]:
        for m in METHODS:
            before = rank(score(board_inputs(bc, cells), c.flow_type, m, size), boards)
            after = rank(score(board_inputs(bc, boosted), c.flow_type, m, size), boards)
            pos_b = pd.Series(range(1, len(before) + 1), index=before.index)[planted]
            pos_a = pd.Series(range(1, len(after) + 1), index=after.index)[planted]
            rows.append({"A 지표": size, "정규화": m,
                         "심은 게시대 평균 순위 (전→후)": f"{pos_b.mean():.0f} → {pos_a.mean():.0f}",
                         "평균 상승 칸": f"{(pos_b - pos_a).mean():.0f}",
                         "TOP 20 진입 (전→후)": f"{(pos_b <= 20).sum()} → {(pos_a <= 20).sum()}",
                         "후보 수": len(before)})
    return rows


def season_inputs(fact, board_cell, boards, c) -> dict[str, pd.DataFrame]:
    """계절별 게시대 입력값(N, T, Tf). 정규화만 바꿔 여러 번 쓰도록 한 번만 계산한다."""
    bc = board_cell[board_cell["board_id"].isin(candidates(boards, c))]
    return {season: board_inputs(bc, cell_values(fact, Criteria(**{**c.__dict__, "period": season})))
            for season in ["SPRING", "SUMMER", "AUTUMN", "WINTER"]}


def stability(seasons: dict[str, pd.DataFrame], boards, flow_type, method, size, span=2.0) -> dict:
    ranked = {k: rank(score(v, flow_type, method, size, lift_span=span), boards) for k, v in seasons.items()}
    pairs = list(combinations(ranked, 2))
    keep = np.mean([len(set(top(ranked[a])) & set(top(ranked[b], 10))) / TOP for a, b in pairs]
                   + [len(set(top(ranked[b])) & set(top(ranked[a], 10))) / TOP for a, b in pairs])
    return {"TOP5 평균 겹침(Jaccard)": f"{np.mean([jaccard(top(ranked[a]), top(ranked[b])) for a, b in pairs]):.2f}",
            "TOP5가 다른 계절 TOP10에 남는 비율": f"{keep:.2f}",
            "전체 순위 상관(Spearman)": f"{np.mean([spearman(ranked[a], ranked[b]) for a, b in pairs]):.3f}"}


def e3_seasons(seasons, boards, c) -> list[dict]:
    return [{"A 지표": size, "정규화": m, **stability(seasons, boards, c.flow_type, m, size)}
            for size in ["N", "T"] for m in METHODS]


def e5_span(fact, board_cell, boards, c, seasons) -> list[dict]:
    """hybrid의 리프트 범위(span)를 바꿔 노이즈·신호 반응·안정성을 함께 본다."""
    cells = cell_values(fact, c)
    bc = board_cell[board_cell["board_id"].isin(candidates(boards, c))]
    inputs = board_inputs(bc, cells)
    planted, boosted = plant(cells, bc)
    boosted_inputs = board_inputs(bc, boosted)
    rows = []
    for size in ["N", "T"]:
        for span in [1.25, 1.5, 2.0, 3.0]:
            s = score(inputs, c.flow_type, "hybrid", size, lift_span=span)
            noise = np.sqrt(sum(s[k + "_pts"].var() for k in ["B", "C"] if k + "_pts" in s))
            before = position(rank(s, boards), planted)
            after = position(rank(score(boosted_inputs, c.flow_type, "hybrid", size, lift_span=span), boards), planted)
            st = stability(seasons, boards, c.flow_type, "hybrid", size, span)
            rows.append({"A 지표": size, "span": span, "B+C 노이즈 배점(표준편차)": f"{noise:.1f}",
                         "신호 심은 곳 평균 상승 칸": f"{(before - after).mean():.0f}",
                         "TOP5→다른 계절 TOP10": st["TOP5가 다른 계절 TOP10에 남는 비율"],
                         "계절 순위 상관": st["전체 순위 상관(Spearman)"]})
    return rows


def e4_kernels(fact, board_cell, boards, c) -> tuple[list[dict], str]:
    cells = cell_values(fact, c)
    bc = board_cell[board_cell["board_id"].isin(candidates(boards, c))]
    ranked = {kern: rank(score(board_inputs(bc, cells, kern), c.flow_type, "hybrid"), boards)
              for kern in ["sum", "equal", "inverse", "gaussian"]}
    center_of = bc[bc["is_center"]].set_index("board_id")["cell_id"]
    rows = []
    for kern, r in ranked.items():
        shared = r.join(center_of.rename("center"))
        groups = shared.groupby("center")["score"]
        multi = groups.size() > 1
        distinct = (groups.nunique()[multi] > 1).sum()
        rows.append({"가중 방식": kern, "TOP5": ", ".join(map(str, top(r))),
                     "단순 합 대비 순위 상관": f"{spearman(ranked['sum'], r):.3f}",
                     "중심 Cell 공유 묶음 중 점수 구분됨": f"{distinct} / {multi.sum()}"})
    # 예시 게시대 하나의 Cell별 가중치
    sample = bc["board_id"].iloc[0]
    one = bc[bc["board_id"] == sample].sort_values("distance_meters").copy()
    lines = [f"예시: 게시대 {sample}의 Cell별 가중치 (거리 = 게시대 → Cell 중심)", "",
             "| Cell | 중심 | 거리(m) | 균등 | 거리 반비례 | 가우스 σ=250m |", "|---|---|---|---|---|---|"]
    weights = {}
    for kern in ["equal", "inverse", "gaussian"]:
        w = one.copy()
        nb = ~w["is_center"]
        kv = neighbor_kernel(w.loc[nb, "distance_meters"].to_numpy(float), kern)
        w["w"] = 0.8
        w.loc[nb, "w"] = 0.2 * kv / kv.sum()
        weights[kern] = w["w"]
    for i, r in one.iterrows():
        lines.append(f"| {r.cell_id} | {'●' if r.is_center else ''} | {r.distance_meters:.0f} | "
                     f"{weights['equal'][i]:.3f} | {weights['inverse'][i]:.3f} | {weights['gaussian'][i]:.3f} |")
    return rows, "\n".join(lines)


def e6_baseline(fact, board_cell, boards) -> list[dict]:
    """PRD N16: 추천 TOP 10의 타깃 인구 합 vs 같은 후보에서 전체 유동이 많은 순 10개의 타깃 인구 합.

    팀 결정(2026-11-21): 중심 0.8 가중을 유지하므로 T·N은 점수와 같은 3×3 가중 합으로 잰다.
    참고로 상세 화면과 같은 3×3 균등 합으로 잰 비율도 함께 적는다.
    """
    rows = []
    for i, c in enumerate(REPRESENTATIVE, 1):
        cells = cell_values(fact, c)
        bc = board_cell[board_cell["board_id"].isin(candidates(boards, c))]
        weighted = board_inputs(bc, cells)
        plain = board_inputs(bc, cells, "sum")
        ours = top(rank(score(weighted, c.flow_type), boards), 10)
        base = list(weighted.sort_values(["N"], ascending=False).index[:10])
        base_plain = list(plain.sort_values(["N"], ascending=False).index[:10])
        ratio = weighted.loc[ours, "T"].sum() / weighted.loc[base, "T"].sum()
        ratio_plain = plain.loc[ours, "T"].sum() / plain.loc[base_plain, "T"].sum()
        rows.append({"조건": i, "후보": len(weighted), "추천 TOP10 / 유동순 10개 (가중 T)": f"{ratio:.3f}",
                     "통과(>1)": "O" if ratio > 1 else "X",
                     "참고: 3×3 균등 합 기준": f"{ratio_plain:.3f}"})
    return rows


def e7_sensitivity(fact, board_cell, boards) -> list[dict]:
    """PRD N17: 배점 ±10점 변형 4종에서 TOP 10 유지 개수."""
    variants = {"A+10 B-10": {"A": 50, "B": 30, "C": 20}, "A-10 B+10": {"A": 30, "B": 50, "C": 20},
                "C+10 B-10": {"A": 40, "B": 30, "C": 30}, "C-10 B+10": {"A": 40, "B": 50, "C": 10}}
    rows = []
    for i, c in enumerate(REPRESENTATIVE[:5], 1):
        bc = board_cell[board_cell["board_id"].isin(candidates(boards, c))]
        inputs = board_inputs(bc, cell_values(fact, c))
        base = top(rank(score(inputs, c.flow_type), boards), 10)
        row = {"조건": i, "유형": c.flow_type}
        kept = []
        for name, pts in variants.items():
            v = top(rank(score(inputs, c.flow_type, points=pts), boards), 10)
            kept.append(len(set(base) & set(v)))
            row[name] = kept[-1]
        row["평균 유지"] = f"{np.mean(kept):.1f}"
        rows.append(row)
    return rows


def e8_shrink(fact, board_cell, boards) -> list[dict]:
    pre = []
    for c in REPRESENTATIVE:
        cells = cell_values(fact, c)
        bc = board_cell[board_cell["board_id"].isin(candidates(boards, c))]
        pre.append((c, cells, bc, board_inputs(bc, cells)))
    planted_sets = [(c, bc, w, *plant(cells, bc)) for c, cells, bc, w in pre[:2]]
    rows = []
    for sh in [0.0, 0.25, 0.5, 1.0, 2.0]:
        ratios = []
        for c, _, _, w in pre:
            ours = top(rank(score(w, c.flow_type, shrink=sh), boards), 10)
            base = list(w.sort_values("N", ascending=False).index[:10])
            ratios.append(w.loc[ours, "T"].sum() / w.loc[base, "T"].sum())
        gains = []
        for c, bc, w, planted, boosted in planted_sets:
            before = position(rank(score(w, c.flow_type, shrink=sh), boards), planted)
            after = position(rank(score(board_inputs(bc, boosted), c.flow_type, shrink=sh), boards), planted)
            gains.append(f"{(before - after).mean():.0f}")
        rows.append({"shrink": sh, "N16 최저 비율": f"{min(ratios):.3f}", "N16 평균 비율": f"{np.mean(ratios):.3f}",
                     "신호 심은 곳 상승 칸 S1 / S2": " / ".join(gains)})
    return rows


def scenario_top(fact, board_cell, boards, c) -> list[dict]:
    r = recommend(fact, board_cell, boards, c, "hybrid")
    rows = []
    for i, (bid, x) in enumerate(r.head(TOP).iterrows(), 1):
        rows.append({"순위": i, "board_id": bid, "게시대": boards.loc[bid, "name"], "점수": f"{x.score:.1f}",
                     "A 유동규모 (0~1)": f"{x.A_pts:.1f} ({x.A_norm:.2f})",
                     "B 타깃비율 (원값)": f"{x.B_pts:.1f} ({x.B:.3f})",
                     "C 유형비중 (원값)": f"{x.C_pts:.1f} ({x.C:.3f})" if "C_pts" in r else "-"})
    return rows


def main() -> None:
    mode = sys.argv[1] if len(sys.argv) > 1 else "fake"
    d, fact, board_cell, boards = load_set(mode)
    out = [f"# 추천 점수 시뮬레이션 ({mode})", "",
           f"후보 게시대 {len(boards)}개, Cell {fact['cell_id'].nunique():,}개, 인구 행 {len(fact):,}행.",
           ("정규화: percentile = 모든 지표 후보 내 백분위 / minmax = 최소·최대 / "
            "hybrid(채택) = A는 log 척도 최소·최대, B·C는 후보 평균 대비 배수(log, 평균=0.5, 2배=1, 절반=0)"), ""]
    for name, c in SCENARIOS.items():
        print(f"[{name}] 계산 중...", flush=True)
        seasons = season_inputs(fact, board_cell, boards, c)
        out += [f"## {name}", "", f"조건: `{c}`", "",
                "### TOP 5 (hybrid, 인접 가우스 가중)", "", md_table(scenario_top(fact, board_cell, boards, c)), "",
                "### E1 지표별 배점 표준편차", "", md_table(e1_noise(fact, board_cell, boards, c)), "",
                "### E2 타깃 신호 심기 (후보 10곳 중심 Cell 타깃 +50%)", "",
                md_table(e2_planted(fact, board_cell, boards, c)), "",
                "### E3 계절별 TOP 5 안정성", "", md_table(e3_seasons(seasons, boards, c)), "",
                "### E5 hybrid 리프트 범위(span) 조정", "", md_table(e5_span(fact, board_cell, boards, c, seasons)), ""]
        rows, sample = e4_kernels(fact, board_cell, boards, c)
        out += ["### E4 인접 Cell 가중 방식 (hybrid)", "", md_table(rows), "", sample, ""]
    print("[PRD N16·N17 점검] 계산 중...", flush=True)
    out += ["## PRD 추천 품질 점검", "",
            "대표 조건: " + " / ".join(f"{i}) `{c}`" for i, c in enumerate(REPRESENTATIVE, 1)), "",
            "### E6 N16 기준선 대비 (통과 기준: 10개 중 8개 이상)", "", md_table(e6_baseline(fact, board_cell, boards)), "",
            "### E7 N17 배점 민감도 (통과 기준: TOP 10 중 평균 7개 이상 유지)", "",
            md_table(e7_sensitivity(fact, board_cell, boards)), "",
            "### E8 비율 보정 세기 (채택 0.5)", "", md_table(e8_shrink(fact, board_cell, boards)), ""]
    target = d / "simulation"
    target.mkdir(exist_ok=True)
    (target / "report.md").write_text("\n".join(out), encoding="utf-8")
    print("\n".join(out))
    print(f"\n저장: {target / 'report.md'}")


if __name__ == "__main__":
    main()
