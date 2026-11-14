import { useState } from 'react'
import type { PopulationQuery, RecommendationItem } from '../lib/types'
import { AGE_LABEL, CITY_LABEL, DEFAULT_QUERY, PURPOSE_LABEL, TIME_BANDS } from '../lib/constants'
import './RecommendationList.css'

interface Props {
  /** 추천 결과. 아직 분석하지 않았으면 null */
  items: RecommendationItem[] | null
  /** 이 결과를 만든 조건. 상단에 요약으로 보여준다. */
  query: PopulationQuery
  selectedBoardId: string | null
  onSelect: (boardId: string) => void
  /** '조건 수정'을 눌렀을 때. 조건 패널로 돌아간다. */
  onEditConditions: () => void
  /** 왼쪽 탭에서 다른 패널을 골랐을 때 true */
  hidden: boolean
}

const PAGE_SIZE = 10
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
 * 추천 결과 패널. 왼쪽 탭에서 '추천 결과'를 고르면 조건 패널 자리에 뜬다. 처음 10개를 보여주고 10개씩 더 본다.
 * 상단에 이 결과를 만든 조건을 요약하고 '조건 수정'으로 조건 패널에 돌아갈 수 있다.
 * 카드는 순위·이름·위치·점수만 보여준다(추천 이유는 우측 상세에 있다). 카드를 누르면 지도가 그 게시대로 이동한다.
 */
function RecommendationList({ items, query, selectedBoardId, onSelect, onEditConditions, hidden }: Props) {
  const [shown, setShown] = useState(PAGE_SIZE)

  if (!items) {
    return (
      <section className="panel recommend-list" hidden={hidden}>
        <h2>추천 결과</h2>
        <p className="placeholder">조건 설정에서 '추천 분석하기'를 누르면 결과가 여기에 나옵니다.</p>
      </section>
    )
  }
  return (
    <section className="panel recommend-list" hidden={hidden}>
      <h2>추천 결과 <small>{items.length}곳</small></h2>

      <div className="rec-conditions">
        <ul aria-label="적용한 조건">
          {summarize(query).map((text) => <li key={text}>{text}</li>)}
        </ul>
        <button type="button" className="link" onClick={onEditConditions}>조건 수정</button>
      </div>

      <p className="notice sample-notice">시안입니다. 순위·점수·추천 이유는 예시 값이며 추천 API 연결 전입니다.</p>

      {items.length === 0 && <p className="placeholder">조건에 맞는 게시대가 없습니다.</p>}

      <ol className="rec-cards">
        {items.slice(0, shown).map((item) => (
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

      {shown < items.length && (
        <button type="button" className="rec-more" onClick={() => setShown(shown + PAGE_SIZE)}>{PAGE_SIZE}개 더 보기</button>
      )}
    </section>
  )
}

export default RecommendationList
