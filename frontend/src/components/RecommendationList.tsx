import { useState } from 'react'
import type { RecommendationItem } from '../lib/types'
import { CITY_LABEL, PURPOSE_LABEL } from '../lib/constants'
import './RecommendationList.css'

interface Props {
  /** 추천 결과. 아직 분석하지 않았으면 null */
  items: RecommendationItem[] | null
  selectedBoardId: string | null
  onSelect: (boardId: string) => void
  /** 왼쪽 탭에서 다른 패널을 골랐을 때 true */
  hidden: boolean
}

const PAGE_SIZE = 10

/**
 * 추천 결과 패널. 왼쪽 탭에서 '추천 결과'를 고르면 조건 패널 자리에 뜬다. 처음 10개를 보여주고 10개씩 더 본다.
 * 카드를 누르면 지도가 그 게시대로 이동하고 우측에 상세가 뜬다.
 */
function RecommendationList({ items, selectedBoardId, onSelect, hidden }: Props) {
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
      <p className="notice sample-notice">시안입니다. 순위·점수·추천 이유는 예시 값이며 추천 API 연결 전입니다.</p>

      {items.length === 0 && <p className="placeholder">조건에 맞는 게시대가 없습니다.</p>}

      <ol className="rec-cards">
        {items.slice(0, shown).map((item) => (
          <li key={item.boardId}>
            <button type="button" className={`rec-card${item.boardId === selectedBoardId ? ' active' : ''}`} onClick={() => onSelect(item.boardId)}>
              <span className="rec-rank">{item.rank}</span>
              <span className="rec-body">
                <b>{item.name}</b>
                <span className="rec-meta">
                  <span className={`badge ${item.purpose}`}>{PURPOSE_LABEL[item.purpose]}</span>
                  {CITY_LABEL[item.city ?? ''] ?? '-'} {item.dong ?? ''}
                </span>
                <span className="rec-reason">{item.reason}</span>
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
