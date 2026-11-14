import { useState } from 'react'
import type { Board, BoardPopulation, DistributionPoint, RecommendationItem } from '../lib/types'
import { AGE_LABEL, CITY_LABEL, PURPOSE_LABEL, formatValue, timeLabel } from '../lib/constants'

interface Props {
  board: Board | null
  population: BoardPopulation | null
  loading: boolean
  error: string | null
  /** 이 게시대가 추천 결과에 들어 있을 때의 추천 정보. 없으면 점수를 표시하지 않는다. */
  recommendation: RecommendationItem | null
  /** 접혀 있으면 true (오른쪽 '상세' 탭만 보인다) */
  hidden: boolean
  /** 패널 오른쪽 위 접기 버튼 */
  onCollapse: () => void
}

/** 패널 오른쪽 위의 접기 버튼 */
function CollapseButton({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" className="panel-collapse" onClick={onClick} title="상세 접기" aria-label="상세 접기">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M9 6l6 6-6 6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" fill="none" />
      </svg>
    </button>
  )
}

const GENDER_LABEL: Record<string, string> = { MALE: '남성', FEMALE: '여성', UNKNOWN: '미상' }
const SERVICE_LABEL: Record<string, string> = { RESIDENCE: '거주', WORK: '근무', VISIT: '방문' }

const TABS = [['time', '시간대'], ['people', '성별·연령'], ['service', '서비스']] as const
type Tab = (typeof TABS)[number][0]

/** 차트 라이브러리는 미정이므로 데모에서는 CSS 막대로 표시한다. */
function Bars({ items }: { items: { label: string; value: number | null; highlight?: boolean }[] }) {
  const max = Math.max(1, ...items.map((i) => i.value ?? 0))
  return (
    <div className="bars">
      {items.map((i) => (
        <div key={i.label} className="bar-row">
          <span className="bar-label">{i.label}</span>
          <span className="bar-track">
            <span className={`bar-fill${i.highlight === false ? ' dim' : ''}`} style={{ width: `${((i.value ?? 0) / max) * 100}%` }} />
          </span>
          <span className="bar-value">{formatValue(i.value)}</span>
        </div>
      ))}
    </div>
  )
}

/** 세로 막대. 조건에 포함된 항목 중 가장 큰 값만 진하게 표시하고 수치를 붙인다. */
function Columns({ items }: { items: { label: string; short: string; value: number | null; highlight?: boolean }[] }) {
  const max = Math.max(1, ...items.map((i) => i.value ?? 0))
  const peak = Math.max(...items.filter((i) => i.highlight !== false).map((i) => i.value ?? 0))
  return (
    <div className="cols">
      {items.map((i) => {
        const isPeak = i.highlight !== false && (i.value ?? 0) === peak && peak > 0
        return (
          <div key={i.label} className="col" title={`${i.label} ${formatValue(i.value)}`}>
            <span className="col-value">{isPeak ? formatValue(i.value) : ''}</span>
            <span className="col-track">
              <span className={`col-bar${isPeak ? ' peak' : ''}${i.highlight === false ? ' dim' : ''}`} style={{ height: `${((i.value ?? 0) / max) * 100}%` }} />
            </span>
            <span className="col-label">{i.short}</span>
          </div>
        )
      })}
    </div>
  )
}

/** 체류·보행·주행 비율을 하나의 누적 막대로 보여준다. */
function FlowStack({ items }: { items: { label: string; value: number | null; tone: 1 | 2 | 3 }[] }) {
  const total = items.reduce((sum, i) => sum + (i.value ?? 0), 0)
  return (
    <div className="flow">
      <div className="flow-bar">
        {total > 0 && items.map((i) => (
          <span key={i.label} className={`tone${i.tone}`} style={{ width: `${((i.value ?? 0) / total) * 100}%` }} />
        ))}
      </div>
      <ul className="flow-legend">
        {items.map((i) => (
          <li key={i.label}><i className={`dot tone${i.tone}`} />{i.label} <b>{formatValue(i.value)}</b></li>
        ))}
      </ul>
    </div>
  )
}

const dist = (points: DistributionPoint[], labels: Record<string, string>) =>
  points.map((p) => ({ label: labels[p.key] ?? p.key, value: p.population.value }))

/**
 * 게시대 상세 영역. 선택 게시대의 정보와 3×3 영향권 인구 집계·차트를 보여준다.
 * 추천 점수는 추천 결과에 들어 있는 게시대에만 표시한다(지금은 시안용 예시 값).
 */
function BoardDetail({ board, population, loading, error, recommendation, hidden, onCollapse }: Props) {
  const [tab, setTab] = useState<Tab>('time')

  if (!board) {
    return (
      <section className="panel board-detail" hidden={hidden}>
        <CollapseButton onClick={onCollapse} />
        <h2>게시대 상세</h2>
        <p className="placeholder">지도에서 게시대를 선택하세요.</p>
      </section>
    )
  }
  return (
    <section className="panel board-detail" hidden={hidden}>
      <CollapseButton onClick={onCollapse} />
      <h2>{board.name}</h2>
      <div className="badges">
        <span className={`badge ${board.purpose}`}>{PURPOSE_LABEL[board.purpose]}</span>
        {!board.canInquire && <span className="badge muted">문의 불가</span>}
      </div>
      <dl className="info">
        <dt>위치</dt><dd>{CITY_LABEL[board.city ?? ''] ?? '-'} {board.dong ?? ''}</dd>
        <dt>주소</dt><dd>{board.address ?? '-'}</dd>
        <dt>게시면</dt><dd>{board.faceCount ?? '-'}</dd>
        <dt>부착금액</dt><dd>{board.postingFee === null ? '정보 없음' : `${board.postingFee.toLocaleString('ko-KR')}원`}</dd>
      </dl>

      {recommendation && (
        <div className="score-box">
          <div className="hero-top">
            <span>추천 점수 · {recommendation.rank}위</span>
            <span className="pill">시안 예시 값</span>
          </div>
          <b>{recommendation.displayScore}<small>점</small></b>
          <p>{recommendation.reason}</p>
          <details className="score-details">
            <summary>항목별 점수 보기</summary>
            <Bars items={recommendation.scoreBreakdown.map((s) => ({ label: s.label, value: s.contribution }))} />
          </details>
        </div>
      )}

      {loading && <p className="placeholder">인구 정보를 불러오는 중…</p>}
      {error && <p className="error">{error}</p>}
      {!board.hasPopulationData && <p className="placeholder">이 게시대는 인구 데이터가 없습니다.</p>}

      {population && !loading && (
        <>
          <div className="hero">
            <div className="hero-top">
              <span>3×3 영향권 · 전체(체류+보행+주행)</span>
              <span className="pill">{population.matchedCellCount}/{population.expectedCellCount} Cell</span>
            </div>
            <b>{formatValue(population.population.totalFlow.value)}</b>
          </div>

          <h3>유동 유형</h3>
          <FlowStack items={[
            { label: '체류', value: population.population.stay.value, tone: 1 },
            { label: '보행', value: population.population.walk.value, tone: 2 },
            { label: '주행', value: population.population.drive.value, tone: 3 },
          ]} />

          <div className="tabs" role="tablist">
            {TABS.map(([key, label]) => (
              <button key={key} type="button" role="tab" aria-selected={tab === key} className={tab === key ? 'active' : ''}
                onClick={() => setTab(key)}>{label}</button>
            ))}
          </div>

          {tab === 'time' && (
            <>
              <Columns items={population.hourly.map((h) => ({
                label: timeLabel(h.timeBand), short: h.timeBand.slice(0, 2), value: h.value.value, highlight: h.selected,
              }))} />
              <h3>월별</h3>
              <Bars items={population.monthly.map((m) => ({ label: `${m.month}월`, value: m.value.value, highlight: m.selected }))} />
            </>
          )}
          {tab === 'people' && (
            <>
              <h3>성별</h3>
              <Bars items={dist(population.genderDistribution, GENDER_LABEL)} />
              <h3>연령대</h3>
              <Bars items={dist(population.ageDistribution, AGE_LABEL)} />
            </>
          )}
          {tab === 'service' && (
            <>
              <h3>서비스 인구</h3>
              <Bars items={dist(population.servicePopulation, SERVICE_LABEL)} />
            </>
          )}
          <p className="notice">{population.dataNotice}</p>
        </>
      )}
    </section>
  )
}

export default BoardDetail
