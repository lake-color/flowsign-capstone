import { PURPOSE_LABEL, formatValue } from '../map/common'
import type { Board, BoardPopulation, DistributionPoint } from '../types'
import { AGE_LABEL, timeLabel } from '../constants'

interface Props {
  board: Board | null
  population: BoardPopulation | null
  loading: boolean
  error: string | null
}

const CITY_LABEL: Record<string, string> = { ANYANG: '안양시', GUNPO: '군포시', SIHEUNG: '시흥시' }
const GENDER_LABEL: Record<string, string> = { MALE: '남성', FEMALE: '여성', UNKNOWN: '미상' }
const SERVICE_LABEL: Record<string, string> = { RESIDENCE: '거주', WORK: '근무', VISIT: '방문' }

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

const dist = (points: DistributionPoint[], labels: Record<string, string>) =>
  points.map((p) => ({ label: labels[p.key] ?? p.key, value: p.population.value }))

/**
 * 게시대 상세 영역. 선택 게시대의 정보와 3×3 영향권 인구 집계·차트를 보여준다.
 * 추천 점수는 추천 결과에서 선택한 경우에만 표시한다(추천 API는 다음 단계).
 */
function BoardDetail({ board, population, loading, error }: Props) {
  if (!board) {
    return (
      <section className="panel board-detail">
        <h2>게시대 상세</h2>
        <p className="placeholder">지도에서 게시대를 선택하세요.</p>
      </section>
    )
  }
  return (
    <section className="panel board-detail">
      <h2>{board.name}</h2>
      <dl className="info">
        <dt>용도</dt><dd>{PURPOSE_LABEL[board.purpose]}{board.canInquire ? '' : ' (문의 불가)'}</dd>
        <dt>위치</dt><dd>{CITY_LABEL[board.city ?? ''] ?? '-'} {board.dong ?? ''}</dd>
        <dt>주소</dt><dd>{board.address ?? '-'}</dd>
        <dt>게시면</dt><dd>{board.faceCount ?? '-'}</dd>
        <dt>부착금액</dt><dd>{board.postingFee === null ? '정보 없음' : `${board.postingFee.toLocaleString('ko-KR')}원`}</dd>
      </dl>

      {loading && <p className="placeholder">인구 정보를 불러오는 중…</p>}
      {error && <p className="error">{error}</p>}
      {!board.hasPopulationData && <p className="placeholder">이 게시대는 인구 데이터가 없습니다.</p>}

      {population && !loading && (
        <>
          <h3>3×3 영향권 인구 <small>({population.matchedCellCount}/{population.expectedCellCount} Cell)</small></h3>
          <div className="stats">
            <div><b>{formatValue(population.population.totalFlow.value)}</b><span>전체(체류+보행+주행)</span></div>
            <div><b>{formatValue(population.population.stay.value)}</b><span>체류</span></div>
            <div><b>{formatValue(population.population.walk.value)}</b><span>보행</span></div>
            <div><b>{formatValue(population.population.drive.value)}</b><span>주행</span></div>
          </div>

          <h3>시간대별</h3>
          <Bars items={population.hourly.map((h) => ({ label: timeLabel(h.timeBand), value: h.value.value, highlight: h.selected }))} />
          <h3>월별</h3>
          <Bars items={population.monthly.map((m) => ({ label: `${m.month}월`, value: m.value.value, highlight: m.selected }))} />
          <h3>성별</h3>
          <Bars items={dist(population.genderDistribution, GENDER_LABEL)} />
          <h3>연령대</h3>
          <Bars items={dist(population.ageDistribution, AGE_LABEL)} />
          <h3>서비스 인구</h3>
          <Bars items={dist(population.servicePopulation, SERVICE_LABEL)} />
          <p className="notice">{population.dataNotice}</p>
        </>
      )}
    </section>
  )
}

export default BoardDetail
