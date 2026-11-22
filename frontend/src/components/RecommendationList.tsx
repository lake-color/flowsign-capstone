import type { PopulationQuery, RecommendationResult } from '../lib/types'
import { AGE_LABEL, CITY_LABEL, DEFAULT_QUERY, PURPOSE_LABEL, TIME_BANDS } from '../lib/constants'
import './RecommendationList.css'

interface Props {
  /** 추천 결과(지금까지 받은 페이지를 이어 붙인 것). 아직 한 번도 성공하지 않았으면 null */
  result: RecommendationResult | null
  /** 이 결과를 만든 조건. 상단에 요약으로 보여준다. */
  query: PopulationQuery
  /** 새로 분석 중('analyze')이면 이전 결과를 둔 채 안내하고, '더 보기' 중('more')이면 버튼에 표시한다. */
  loading: 'analyze' | 'more' | null
  error: string | null
  /** 다음 10개 요청 */
  onMore: () => void
  selectedBoardId: string | null
  onSelect: (boardId: string) => void
  /** '조건 수정'을 눌렀을 때. 조건 패널로 돌아간다. */
  onEditConditions: () => void
  /** 왼쪽 탭에서 다른 패널을 골랐을 때 true */
  hidden: boolean
}

const GENDER_TEXT = { ALL: '성별 전체', MALE: '남성', FEMALE: '여성' } as const
const FLOW_TEXT = { ALL: '유동 전체', STAY: '체류', WALK: '보행', DRIVE: '주행' } as const
const SEASON_TEXT = { SPRING: '봄', SUMMER: '여름', AUTUMN: '가을', WINTER: '겨울' } as const
const DAY_TEXT = { ALL: '', WEEKDAY: '주중', WEEKEND: '주말' } as const

/** 조건을 짧은 문구 목록으로 줄인다. 기본값(전체)인 항목은 '전체'로 표시한다. */
function summarize(q: PopulationQuery): string[] {
  const ages = DEFAULT_QUERY.ageGroups
  const ageText = q.ageGroups.length === ages.length ? '전 연령' : q.ageGroups.map((a) => AGE_LABEL[a]).join('·')
  const timeText = q.timeBands.length === TIME_BANDS.length ? '전 시간대' : `${q.timeBands.length}개 시간대`
  const periodText = q.periodKind === 'YEAR' ? '연간' : q.periodKind === 'SEASON' ? SEASON_TEXT[q.season] : `${q.month}월`
  return [GENDER_TEXT[q.gender], ageText, FLOW_TEXT[q.flowType], timeText, periodText, DAY_TEXT[q.dayType]].filter(Boolean)
}

/**
 * 추천 결과 패널. 왼쪽 탭에서 '추천 결과'를 고르면 조건 패널 자리에 뜬다. 처음 10개를 받고 '더 보기'로 10개씩 더 요청한다.
 * 상단에 이 결과를 만든 조건을 요약하고 '조건 수정'으로 조건 패널에 돌아갈 수 있다.
 * 카드는 순위·이름·위치·점수만 보여준다(추천 이유는 우측 상세에 있다). 카드를 누르면 지도가 그 게시대로 이동한다.
 */
function RecommendationList({ result, query, loading, error, onMore, selectedBoardId, onSelect, onEditConditions, hidden }: Props) {
  if (!result) {
    return (
      <section className="panel recommend-list" hidden={hidden}>
        <h2>추천 결과</h2>
        {loading && <p className="placeholder">추천 분석 중…</p>}
        {error && <p className="error">추천 결과를 불러오지 못했습니다: {error}</p>}
        {!loading && !error && <p className="placeholder">조건 설정에서 '추천 분석하기'를 누르면 결과가 여기에 나옵니다.</p>}
      </section>
    )
  }
  const { items } = result
  return (
    <section className="panel recommend-list" hidden={hidden}>
      <div className="rec-head">
        <h2>추천 결과 <small>{result.totalItems}곳</small></h2>
        <button type="button" className="link" onClick={onEditConditions}>조건 수정</button>
      </div>

      <ul className="rec-conditions" aria-label="적용한 조건">
        {summarize(query).map((text) => <li key={text}>{text}</li>)}
      </ul>

      <p className="notice sample-notice">{result.notice}</p>

      {/* 새 분석이 끝나거나 실패하기 전까지 이전 결과를 그대로 보여준다 */}
      {loading === 'analyze' && <p className="placeholder">추천 분석 중…</p>}
      {error && <p className="error">추천 결과를 불러오지 못했습니다: {error}</p>}

      {items.length === 0 && <p className="placeholder">조건에 맞는 게시대가 없습니다.</p>}

      <ol className="rec-cards">
        {items.map((item) => (
          <li key={item.boardId}>
            <button type="button" className={`rec-card${item.boardId === selectedBoardId ? ' active' : ''}`}
              title={item.reason} onClick={() => onSelect(item.boardId)}>
              <span className="rec-rank">{item.rank}</span>
              <span className="rec-body">
                <b>{item.name}</b>
                <span className="rec-meta">
                  <span className={`badge ${item.purpose}`}>{PURPOSE_LABEL[item.purpose]}</span>
                  {CITY_LABEL[item.city ?? ''] ?? '-'} {item.dong ?? ''}
                </span>
              </span>
              <span className="rec-score"><b>{item.displayScore}</b>점</span>
            </button>
          </li>
        ))}
      </ol>

      {result.hasNext && (
        <button type="button" className="rec-more" disabled={loading !== null} onClick={onMore}>
          {loading === 'more' ? '불러오는 중…' : '10개 더 보기'}
        </button>
      )}
    </section>
  )
}

export default RecommendationList
