import { compactValue, formatValue } from '../lib/constants'

/**
 * 상세 패널용 SVG 차트 (차트 라이브러리는 미정이라 의존성 없이 그린다).
 * 색은 index.css의 --series-* 변수(라이트·다크 각각 검증한 범주 팔레트)와 --accent를 쓴다.
 * 마우스오버는 SVG <title>로 값을 보여준다.
 */

export interface DonutItem {
  key: string
  label: string
  value: number | null
  /** CSS 색 (예: 'var(--series-1)') */
  color: string
}

const DONUT_R = 42
const DONUT_STROKE = 16
const DONUT_GAP = 2 // 조각 사이 2px 틈 (둘레 길이 기준)

/** 도넛 + 범례(이름·값·비율). 값이 null이면 0으로 보고, 합이 0이면 '데이터 없음'. */
export function Donut({ items, centerLabel }: { items: DonutItem[]; centerLabel: string }) {
  const total = items.reduce((sum, i) => sum + (i.value ?? 0), 0)
  const circumference = 2 * Math.PI * DONUT_R
  const visible = items.filter((i) => (i.value ?? 0) > 0)
  const gap = visible.length > 1 ? DONUT_GAP : 0

  const lengths = visible.map((i) => ((i.value ?? 0) / total) * circumference)
  const arcs = visible.map((item, n) => ({
    item,
    dash: Math.max(0, lengths[n] - gap),
    offset: lengths.slice(0, n).reduce((sum, l) => sum + l, 0), // 앞 조각들 길이 합
  }))

  return (
    <div className="donut">
      <svg viewBox="0 0 120 120" role="img" aria-label={`${centerLabel} ${formatValue(total)}`}>
        <circle cx="60" cy="60" r={DONUT_R} className="donut-track" strokeWidth={DONUT_STROKE} fill="none" />
        {total > 0 && arcs.map(({ item, dash, offset }) => (
          <circle key={item.key} cx="60" cy="60" r={DONUT_R} fill="none" stroke={item.color} strokeWidth={DONUT_STROKE}
            strokeDasharray={`${dash} ${circumference - dash}`} strokeDashoffset={-offset} transform="rotate(-90 60 60)">
            <title>{`${item.label} ${formatValue(item.value)} (${(((item.value ?? 0) / total) * 100).toFixed(1)}%)`}</title>
          </circle>
        ))}
        <text x="60" y="56" textAnchor="middle" className="donut-caption">{centerLabel}</text>
        <text x="60" y="72" textAnchor="middle" className="donut-total">{total > 0 ? formatValue(total) : '-'}</text>
      </svg>
      <ul className="donut-legend">
        {items.map((i) => (
          <li key={i.key}>
            <i style={{ background: i.color }} />
            <span>{i.label}</span>
            <b>{formatValue(i.value)}</b>
            <small>{total > 0 ? `${(((i.value ?? 0) / total) * 100).toFixed(0)}%` : '-'}</small>
          </li>
        ))}
      </ul>
    </div>
  )
}

export interface LinePoint {
  label: string
  short: string
  value: number | null
  /** 조건에 포함된 구간이면 true. 아니면 점을 흐리게 그린다. */
  selected: boolean
}

/** 그래프 아래의 작은 단위 설명. 예: (명/일 · 선택 기간 하루 평균) */
export function ChartUnit({ children }: { children: string }) {
  return <p className="chart-unit">({children})</p>
}

/** 꺾은선 그래프 (단일 계열). 값이 없는 구간에서 선을 끊고, 모든 점에 수치를 붙인다(조건 밖 구간은 흐리게). */
export function LineChart({ points }: { points: LinePoint[] }) {
  const width = 300
  const height = 140
  const pad = { top: 24, right: 16, bottom: 24, left: 16 }
  const max = Math.max(1, ...points.map((p) => p.value ?? 0))
  const step = (width - pad.left - pad.right) / Math.max(1, points.length - 1)
  const x = (i: number) => pad.left + i * step
  const y = (v: number) => pad.top + (1 - v / max) * (height - pad.top - pad.bottom)

  // null에서 끊긴 구간별 경로
  const segments: string[] = []
  let current = ''
  points.forEach((p, i) => {
    if (p.value === null) {
      if (current) segments.push(current)
      current = ''
      return
    }
    current += `${current ? 'L' : 'M'}${x(i).toFixed(1)},${y(p.value).toFixed(1)}`
  })
  if (current) segments.push(current)

  return (
    <svg className="line-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="시간대별 인구 꺾은선 그래프">
      <line x1={pad.left} x2={width - pad.right} y1={height - pad.bottom} y2={height - pad.bottom} className="line-base" />
      {segments.map((d) => <path key={d} d={d} className="line-path" />)}
      {points.map((p, i) => {
        return (
          <g key={p.label}>
            {p.value !== null && (
              <>
                <circle cx={x(i)} cy={y(p.value)} r={4} className={`line-dot${p.selected ? '' : ' dim'}`} />
                <text x={x(i)} y={y(p.value) - 10} textAnchor="middle" className={`line-value${p.selected ? '' : ' dim'}`}>
                  {compactValue(p.value)}
                </text>
              </>
            )}
            {/* 마우스오버 영역은 점보다 넓게 */}
            <rect x={x(i) - step / 2} y={pad.top - 10} width={step} height={height - pad.top - pad.bottom + 10} className="line-hit">
              <title>{`${p.label} ${formatValue(p.value)}`}</title>
            </rect>
            <text x={x(i)} y={height - 6} textAnchor="middle" className="line-label">{p.short}</text>
          </g>
        )
      })}
    </svg>
  )
}
