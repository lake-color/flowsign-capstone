import type { Board, RecommendationItem } from './types'

// 시안용 가짜 추천 결과. 추천 API(POST /recommendations/search)가 생기면 이 파일은 지운다.
// 게시대는 이미 불러온 실제 목록에서 고르지만, 순위·점수·이유는 화면 확인용으로 지어낸 값이다.

const PARTS = [
  { key: 'A', label: '타깃 규모', weight: 0.4 },
  { key: 'B', label: '타깃 비율', weight: 0.3 },
  { key: 'C', label: '유형 적합도', weight: 0.2 },
  { key: 'D', label: '전체 규모', weight: 0.1 },
]
const SAMPLE_COUNT = 20

export function sampleRecommendations(boards: Board[]): RecommendationItem[] {
  return boards
    .filter((b) => b.canInquire)
    .slice(0, SAMPLE_COUNT)
    .map((b, i) => {
      const score = 94 - i * 3
      return {
        rank: i + 1,
        boardId: b.boardId,
        name: b.name,
        city: b.city,
        dong: b.dong,
        purpose: b.purpose,
        displayScore: score,
        reason: '선택한 타깃 규모와 비율이 높은 게시대입니다. (예시 문구)',
        scoreBreakdown: PARTS.map((p) => ({ key: p.key, label: p.label, contribution: score * p.weight })),
      }
    })
}
