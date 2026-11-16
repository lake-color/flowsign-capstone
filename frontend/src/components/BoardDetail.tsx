import { useState } from 'react'
import type { Board, BoardPopulation, BoardStores, DistributionPoint, RecommendationItem, StoreCategory } from '../lib/types'
import { AGE_LABEL, CITY_LABEL, PURPOSE_LABEL, STORE_CATEGORY_LABEL, compactValue, formatValue, timeLabel } from '../lib/constants'
import { ChartUnit, Donut, LineChart, type DonutItem } from './Charts'

interface Props {
  board: Board | null
  population: BoardPopulation | null
  loading: boolean
  error: string | null
  /** 3×3 Cell 안의 상가. 불러오는 중이거나 실패했으면 null */
  stores: BoardStores | null
  storesError: string | null
  /** 지도에서 강조할 업종. 같은 업종을 다시 누르면 해제된다. */
  highlightCategory: StoreCategory | null
  onSelectCategory: (category: StoreCategory) => void
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

// 도넛 색은 순위가 아니라 항목에 고정한다. 미상은 범주색이 아닌 회색.
const GENDER_COLOR: Record<string, string> = { MALE: 'var(--series-1)', FEMALE: 'var(--series-2)', UNKNOWN: 'var(--series-other)' }
const SERVICE_COLOR: Record<string, string> = { RESIDENCE: 'var(--series-1)', WORK: 'var(--series-2)', VISIT: 'var(--series-3)' }

/**
 * 주변 업종 도넛에 색을 주는 업종 (고정 6개, 이 순서로 그림). 15개를 모두 색으로 나누면 구분이 안 되므로
 * 나머지는 '기타' 회색 한 조각으로 묶고, 전체 업종은 도넛 아래 막대 목록에서 본다.
 */
const DONUT_CATEGORIES: StoreCategory[] = ['RESTAURANT', 'CAFE', 'EDUCATION', 'RETAIL', 'MEDICAL', 'LIVING_SERVICE']

const TABS = [['time', '시간대'], ['people', '성별·연령'], ['service', '서비스'], ['stores', '주변 업종']] as const
type Tab = (typeof TABS)[number][0]

/** 가로 막대 (연령대 등). 차트 라이브러리는 미정이므로 CSS로 그린다. */
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

/** 세로 막대. 모든 막대에 수치를 붙이고, 조건에 포함된 항목 중 가장 큰 막대만 진하게 칠한다. */
function Columns({ items }: { items: { label: string; short: string; value: number | null; highlight?: boolean }[] }) {
  const max = Math.max(1, ...items.map((i) => i.value ?? 0))
  const peak = Math.max(...items.filter((i) => i.highlight !== false).map((i) => i.value ?? 0))
  return (
    <div className="cols">
      {items.map((i) => {
        const isPeak = i.highlight !== false && (i.value ?? 0) === peak && peak > 0
        return (
          <div key={i.label} className="col" title={`${i.label} ${formatValue(i.value)}`}>
            <span className={`col-value${i.highlight === false ? ' dim' : ''}`}>{compactValue(i.value)}</span>
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

/** 3×3 Cell 안 상가의 업종별 개수. 항목을 누르면 지도에서 그 업종을 강조한다. */
function NearbyStores({ stores, error, highlight, onSelect }: {
  stores: BoardStores | null
  error: string | null
  highlight: StoreCategory | null
  onSelect: (category: StoreCategory) => void
}) {
  if (error) return <p className="error">주변 업종을 불러오지 못했습니다: {error}</p>
  if (!stores) return <p className="placeholder">주변 업종을 불러오는 중…</p>
  if (stores.totalCount === 0) return <p className="placeholder">3×3 Cell 안에 상가가 없습니다.</p>
  const max = Math.max(1, ...stores.categories.map((c) => c.count))
  const month = stores.baseMonth ? `${stores.baseMonth.slice(0, 4)}-${stores.baseMonth.slice(4)} 기준 · ` : ''
  const countOf = (category: StoreCategory) => stores.categories.find((c) => c.category === category)?.count ?? 0
  const colored = DONUT_CATEGORIES.reduce((sum, c) => sum + countOf(c), 0)
  const donut: DonutItem[] = [
    ...DONUT_CATEGORIES.map((c, i) => ({ key: c, label: STORE_CATEGORY_LABEL[c], value: countOf(c), color: `var(--series-${i + 1})` })),
    { key: 'OTHER', label: '기타', value: stores.totalCount - colored, color: 'var(--series-other)' },
  ]
  const swatch = (category: StoreCategory) => {
    const i = DONUT_CATEGORIES.indexOf(category)
    return i >= 0 ? `var(--series-${i + 1})` : 'var(--series-other)'
  }
  return (
    <>
      <p className="store-meta">3×3 Cell · {month}총 {stores.totalCount.toLocaleString('ko-KR')}곳</p>
      <Donut items={donut} centerLabel="상가" />
      <ChartUnit>곳 · 3×3 Cell 안 상가 수</ChartUnit>
      <h3>업종별</h3>
      <ul className="store-list">
        {stores.categories.map((c) => (
          <li key={c.category}>
            <button type="button" className={`bar-row${highlight === c.category ? ' active' : ''}`}
              aria-pressed={highlight === c.category} onClick={() => onSelect(c.category)}>
              <span className="bar-label"><i className="swatch" style={{ background: swatch(c.category) }} />{STORE_CATEGORY_LABEL[c.category] ?? c.category}</span>
              <span className="bar-track">
                <span className={`bar-fill${highlight && highlight !== c.category ? ' dim' : ''}`} style={{ width: `${(c.count / max) * 100}%` }} />
              </span>
              <span className="bar-value">{c.count.toLocaleString('ko-KR')}</span>
            </button>
          </li>
        ))}
      </ul>
      <ChartUnit>곳 · 업종별 상가 수</ChartUnit>
      <p className="notice">업종을 누르면 지도의 '주변 업종' 레이어에서 그 업종만 강조합니다. 소상공인시장진흥공단 상가(상권)정보 기준입니다.</p>
    </>
  )
}

const dist = (points: DistributionPoint[], labels: Record<string, string>) =>
  points.map((p) => ({ label: labels[p.key] ?? p.key, value: p.population.value }))

const donutItems = (points: DistributionPoint[], labels: Record<string, string>, colors: Record<string, string>): DonutItem[] =>
  points.map((p) => ({ key: p.key, label: labels[p.key] ?? p.key, value: p.population.value, color: colors[p.key] ?? 'var(--series-other)' }))

/**
 * 게시대 상세 영역. 선택 게시대의 정보와 3×3 영향권 인구 집계·차트를 보여준다.
 * 추천 점수는 추천 결과에 들어 있는 게시대에만 표시한다(지금은 시안용 예시 값).
 */
function BoardDetail({
  board, population, loading, error, stores, storesError, highlightCategory, onSelectCategory, recommendation, hidden, onCollapse,
}: Props) {
  const [tab, setTab] = useState<Tab>('time')
  // 인구값 단위. 서버가 주는 값(현재 '명/일' = 선택 기간 하루 평균)을 그대로 쓴다.
  const unit = population?.population.totalFlow.unitLabel ?? '명/일'

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
            <b>{formatValue(population.population.totalFlow.value)}<small className="hero-unit">{unit}</small></b>
            <p className="hero-caption">선택 기간·시간대의 하루 평균 (기간 합계 ÷ 해당 일수)</p>
          </div>

          <h3>유동 유형</h3>
          <FlowStack items={[
            { label: '체류', value: population.population.stay.value, tone: 1 },
            { label: '보행', value: population.population.walk.value, tone: 2 },
            { label: '주행', value: population.population.drive.value, tone: 3 },
          ]} />
          <ChartUnit>{`${unit} · 선택 기간 하루 평균`}</ChartUnit>
        </>
      )}

      {/* 주변 업종 탭은 인구와 무관하므로 탭 줄은 인구가 없어도 보인다. */}
      <div className="tabs" role="tablist">
        {TABS.map(([key, label]) => (
          <button key={key} type="button" role="tab" aria-selected={tab === key} className={tab === key ? 'active' : ''}
            onClick={() => setTab(key)}>{label}</button>
        ))}
      </div>

      {population && !loading && tab !== 'stores' && (
        <>
          {tab === 'time' && (
            <>
              <h3>시간대별</h3>
              <LineChart points={population.hourly.map((h) => ({
                label: timeLabel(h.timeBand), short: h.timeBand.slice(0, 2), value: h.value.value, selected: h.selected,
              }))} />
              <ChartUnit>{`${unit} · 시간대(2시간)별 하루 평균`}</ChartUnit>
              <h3>월별</h3>
              <Columns items={population.monthly.map((m) => ({
                label: `${m.month}월`, short: String(m.month), value: m.value.value, highlight: m.selected,
              }))} />
              <ChartUnit>{`${unit} · 각 달의 하루 평균 (월 합계 ÷ 그 달 일수)`}</ChartUnit>
            </>
          )}
          {tab === 'people' && (
            <>
              <h3>성별</h3>
              <Donut items={donutItems(population.genderDistribution, GENDER_LABEL, GENDER_COLOR)} centerLabel="합계" />
              <ChartUnit>{`${unit} · 선택 기간 하루 평균`}</ChartUnit>
              <h3>연령대</h3>
              <Bars items={dist(population.ageDistribution, AGE_LABEL)} />
              <ChartUnit>{`${unit} · 선택 기간 하루 평균`}</ChartUnit>
            </>
          )}
          {tab === 'service' && (
            <>
              <h3>서비스 인구</h3>
              <Donut items={donutItems(population.servicePopulation, SERVICE_LABEL, SERVICE_COLOR)} centerLabel="합계" />
              <ChartUnit>{`${unit} · 선택 기간 하루 평균`}</ChartUnit>
            </>
          )}
          <p className="notice">{population.dataNotice}</p>
        </>
      )}

      {tab === 'stores' && (
        <NearbyStores stores={stores} error={storesError} highlight={highlightCategory} onSelect={onSelectCategory} />
      )}
    </section>
  )
}

export default BoardDetail
