package com.capstone.backend.recommendation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 추천 점수 산식 (100점). 팀 초안(2026-11-11)의 배점을 쓰고, 지표를 0~1로 바꾸는 방식은
 * 시나리오 시뮬레이션으로 정했다 (python/src/data_processing/simulate.py, docs/RECOMMENDATION_SCORE.md).
 *
 * <pre>
 * 입력(게시대별, 3×3 Cell 가중 합 — {@link CellWeights}). 배점·리프트 범위·최소 인구는 {@link Params}(설정)
 *   N  = 전체 유동(체류+보행+주행), T = 타깃(성별·연령) 유동, Tf = 타깃의 선택 유동 유형 값
 * 지표와 배점
 *   A 유동 규모  N      40점   log(N)을 후보 안 최소~최대로 0~1
 *   B 타깃 비율  T/N    40점   후보 전체 비율(ΣT/ΣN) 대비 배수를 log 척도로: 같으면 0.5, 2배 이상 1, 절반 이하 0
 *   C 유형 비중  Tf/T   20점   B와 같은 방식 (기준 ΣTf/ΣT)
 *   유동 유형 ALL이면 C가 모두 1이라 의미가 없으므로 빼고 A·B를 50·50으로 키운다.
 * </pre>
 *
 * 백분위(중간보고서 계획안) 대신 이 방식을 쓰는 이유: 백분위는 0.19와 0.20처럼 작은 비율 차이도 0~40점으로
 * 벌려, 가짜 데이터(비율 차이가 노이즈뿐)에서 계절마다 TOP 5가 거의 다 바뀌었다(시뮬레이션 E1·E3).
 * log로 더하므로 A+B는 대략 log(타깃 규모 T)를 보는 것과 같다(T = N × T/N).
 *
 * 순수 계산만 한다(DB·Spring 없음). 정렬은 명세 4.6: 점수↓ → 게시면수↓ → 부착금액↑(없음 뒤) → 게시대 ID↑.
 * Python 참고 구현: python/src/data_processing/scoring.py (규칙을 바꾸면 함께 고친다).
 */
public final class ScoreFormula {

    /** 산식이 바뀌면 올린다. 추천 응답·/meta의 algorithmVersion에 쓴다. */
    public static final String VERSION = "provisional-v2";

    private ScoreFormula() {
    }

    /**
     * 산식 파라미터. 코드 수정 없이 application.yml(flowsign.recommendation.*)로 바꾼다 (PRD N15).
     *
     * @param sizePoints      A 유동 규모 배점
     * @param sharePoints     B 타깃 비율 배점
     * @param flowPoints      C 유형 비중 배점 (유형 ALL이면 빼고 A·B를 같은 비율로 키운다)
     * @param liftSpan        비율 지표가 후보 평균의 몇 배일 때 만점인지 (1/liftSpan배면 0점). 시뮬레이션 E5
     * @param minTargetPerDay 최소 타깃 인구(가중 T, 명/일). 이보다 적으면 추천에서 뺀다 (PRD B4). 0이면 끔
     */
    public record Params(double sizePoints, double sharePoints, double flowPoints, double liftSpan,
                         double minTargetPerDay) {

        /** 팀 초안 배점 40/40/20, 리프트 2배 만점, 최소 인구 기준 없음(EDA 후 결정) */
        public static final Params DEFAULT = new Params(40, 40, 20, 2.0, 0);

        public Params {
            if (sizePoints < 0 || sharePoints < 0 || flowPoints < 0 || sizePoints + sharePoints <= 0) {
                throw new IllegalArgumentException("배점은 0 이상이고 A+B가 0보다 커야 합니다.");
            }
            if (liftSpan <= 1) {
                throw new IllegalArgumentException("liftSpan은 1보다 커야 합니다: " + liftSpan);
            }
        }
    }

    /**
     * 게시대 하나의 점수 입력. n·t·tf는 3×3 가중 합(일평균, 명/일).
     *
     * @param expectedCellCount board_cell의 Cell 수
     * @param matchedCellCount  조건에 맞는 데이터가 있어 계산에 들어간 Cell 수
     */
    public record Input(long boardId, Integer faceCount, Integer postingFee, double n, double t, double tf,
                        int expectedCellCount, int matchedCellCount) {
    }

    /**
     * 점수 구성 한 줄 (명세 4.6 scoreBreakdown).
     *
     * @param rawValue   A는 가중 유동(명/일), B·C는 비율(0~1)
     * @param baseline   B·C의 비교 기준(후보 전체 비율). A는 null
     * @param lift       rawValue / baseline. 추천 이유 문구용("평균의 1.3배"). A는 null
     * @param normalized 0~1
     * @param weight     이 지표의 최대 배점
     * @param points     normalized × weight
     */
    public record Component(String key, String label, double rawValue, Double baseline, Double lift,
                            double normalized, double weight, double points) {
    }

    /** score는 반올림 전 값. 화면 표시용 반올림은 응답을 만들 때 한다. */
    public record Result(Input input, double score, List<Component> components) {
    }

    public static List<Result> scoreAndRank(List<Input> inputs, String flowType) {
        return scoreAndRank(inputs, flowType, Params.DEFAULT);
    }

    /**
     * flowType: ALL / STAY / WALK / DRIVE.
     * 전체 유동이 없는 게시대(n ≤ 0)와 최소 타깃 인구에 못 미치는 게시대는 빼고, 남은 후보끼리 비교한다.
     */
    public static List<Result> scoreAndRank(List<Input> inputs, String flowType, Params params) {
        List<Input> valid = inputs.stream()
                .filter(in -> in.n() > 0 && in.t() >= params.minTargetPerDay())
                .toList();
        if (valid.isEmpty()) {
            return List.of();
        }
        boolean useFlow = !"ALL".equals(flowType);
        double scale = useFlow ? 1 : (params.sizePoints() + params.sharePoints() + params.flowPoints())
                / (params.sizePoints() + params.sharePoints());

        double minLog = Double.POSITIVE_INFINITY;
        double maxLog = Double.NEGATIVE_INFINITY;
        double sumN = 0;
        double sumT = 0;
        double sumTf = 0;
        for (Input in : valid) {
            double log = Math.log(in.n());
            minLog = Math.min(minLog, log);
            maxLog = Math.max(maxLog, log);
            sumN += in.n();
            sumT += in.t();
            sumTf += in.tf();
        }
        double baseShare = sumT / sumN;
        double baseFlow = sumT > 0 ? sumTf / sumT : 0;

        List<Result> results = new ArrayList<>();
        for (Input in : valid) {
            List<Component> parts = new ArrayList<>();
            double sizeNorm = maxLog > minLog ? (Math.log(in.n()) - minLog) / (maxLog - minLog) : 1;
            parts.add(component("A", "유동 규모", in.n(), null, null, sizeNorm, params.sizePoints() * scale));

            double share = in.t() / in.n();
            parts.add(ratioComponent("B", "타깃 비율", share, baseShare, params.sharePoints() * scale,
                    params.liftSpan()));

            if (useFlow) {
                double flow = in.t() > 0 ? in.tf() / in.t() : 0;
                parts.add(ratioComponent("C", "유형 비중", flow, baseFlow, params.flowPoints(), params.liftSpan()));
            }
            double score = parts.stream().mapToDouble(Component::points).sum();
            results.add(new Result(in, score, List.copyOf(parts)));
        }
        results.sort(ORDER);
        return results;
    }

    /** 명세 4.6 정렬. 게시면수가 없으면 가장 적은 것으로, 부착금액이 없으면 가장 뒤로. */
    static final Comparator<Result> ORDER = Comparator
            .comparingDouble(Result::score).reversed()
            .thenComparing(r -> r.input().faceCount(), Comparator.nullsLast(Comparator.<Integer>reverseOrder()))
            .thenComparing(r -> r.input().postingFee(), Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
            .thenComparingLong(r -> r.input().boardId());

    /** 비율 → 0~1. 기준과 같으면 0.5, span배면 1, 1/span배면 0 (log 척도). */
    static double liftScore(double ratio, double baseline, double span) {
        if (baseline <= 0) {
            return 0.5;
        }
        if (ratio <= 0) {
            return 0;
        }
        double value = 0.5 + Math.log(ratio / baseline) / (2 * Math.log(span));
        return Math.max(0, Math.min(1, value));
    }

    private static Component ratioComponent(String key, String label, double ratio, double baseline, double weight,
                                            double span) {
        Double lift = baseline > 0 ? ratio / baseline : null;
        return component(key, label, ratio, baseline, lift, liftScore(ratio, baseline, span), weight);
    }

    private static Component component(String key, String label, double raw, Double baseline, Double lift,
                                       double norm, double weight) {
        return new Component(key, label, raw, baseline, lift, norm, weight, norm * weight);
    }
}
