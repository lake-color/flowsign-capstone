import { useState, type MouseEvent } from 'react'
import { compactValue, formatValue } from '../lib/constants'

/**
 * 상세 패널용 SVG 차트 (차트 라이브러리는 미정이라 의존성 없이 그린다).
 * 색은 index.css의 --series-* 변수(라이트·다크 각각 검증한 범주 팔레트)와 --accent를 쓴다.
 * 마우스오버 값은 원형은 커서 옆 툴팁, 꺾은선·막대는 SVG <title>로 보여준다(월별은 막대 위 수치도 함께).
 */

export interface PieItem {
  key: string
  label: string
  value: number | null
  /** CSS 색 (예: 'var(--series-1)') */
  color: string
}

const PIE_R = 26 // 반지름 26, 두께 52인 원 테두리 = 반지름 52 원형
const PIE_GAP = 1.5 // 조각 사이 틈 (테두리 둘레 길이 기준)

/**
 * 원형 차트 + 범례(이름·값·비율, 마지막 줄 합계). 값이 null이면 0으로 본다.
 * 조각에 마우스를 올리면 커서 옆에 '이름 값 (비율)'을 띄우고 범례의 같은 줄을 강조한다.
 */
export function PieChart({ items, label, className }: { items: PieItem[]; label: string; className?: string }) {
  const [hover, setHover] = useState<{ key: string; x: number; y: number } | null>(null)
  const total = items.reduce((sum, i) => sum + (i.value ?? 0), 0)
  const circumference = 2 * Math.PI * PIE_R
  const visible = items.filter((i) => (i.value ?? 0) > 0)
  const gap = visible.length > 1 ? PIE_GAP : 0

  const lengths = visible.map((i) => ((i.value ?? 0) / total) * circumference)
  const slices = visible.map((item, n) => ({
    item,
    dash: Math.max(0, lengths[n] - gap),
    offset: lengths.slice(0, n).reduce((sum, l) => sum + l, 0), // 앞 조각들 길이 합
  }))
  const percent = (v: number | null) => (total > 0 ? `${(((v ?? 0) / total) * 100).toFixed(0)}%` : '-')
  const hovered = hover ? items.find((i) => i.key === hover.key) : undefined

  // 커서 위치를 원형 영역 기준 좌표로 바꿔 툴팁을 커서 옆에 둔다.
  const track = (key: string) => (e: MouseEvent<SVGCircleElement>) => {
    const box = e.currentTarget.ownerSVGElement?.parentElement?.getBoundingClientRect()
    if (box) setHover({ key, x: e.clientX - box.left, y: e.clientY - box.top })
  }

  return (
    <div className={`pie${className ? ` ${className}` : ''}`}>
      <svg viewBox="0 0 120 120" role="img" aria-label={`${label} 합계 ${formatValue(total)}`}>
        <circle cx="60" cy="60" r={PIE_R * 2} className="pie-track" />
        {total > 0 && slices.map(({ item, dash, offset }) => (
          <circle key={item.key} cx="60" cy="60" r={PIE_R} fill="none" stroke={item.color} strokeWidth={PIE_R * 2}
            strokeDasharray={`${dash} ${circumference - dash}`} strokeDashoffset={-offset} transform="rotate(-90 60 60)"
            className={hover && hover.key !== item.key ? 'dim' : ''}
            onMouseMove={track(item.key)} onMouseLeave={() => setHover(null)} />
        ))}
      </svg>
      {hover && hovered && (
        <div className="pie-tip" style={{ left: hover.x + 12, top: hover.y + 12 }}>
          <i style={{ background: hovered.color }} />
          {hovered.label} <b>{formatValue(hovered.value)}</b> ({percent(hovered.value)})
        </div>
      )}
      <ul className="pie-legend">
        {items.map((i) => (
          <li key={i.key} className={hover?.key === i.key ? 'active' : ''}>
            <i style={{ background: i.color }} />
            <span>{i.label}</span>
            <b>{formatValue(i.value)}</b>
            <small>{percent(i.value)}</small>
          </li>
        ))}
        <li className="total">
          <i />
          <span>합계</span>
          <b>{total > 0 ? formatValue(total) : '-'}</b>
          <small />
        </li>
      </ul>
    </div>
  )
}

/** 그래프 아래의 작은 단위 설명. 예: (명/일 · 선택 기간 하루 평균) */
export function ChartUnit({ children }: { children: string }) {
  return <p className="chart-unit">({children})</p>
}

/**
 * 0부터 최댓값을 덮는 보기 좋은 눈금. 간격은 1·2·2.5·5 × 10ⁿ 중에서 고른다.
 * 예: 최댓값 52, 4칸 → 0, 20, 40, 60 / 최댓값 290, 3칸 → 0, 100, 200, 300
 */
function niceTicks(max: number, count: number): number[] {
  const raw = Math.max(max, 1) / count
  const magnitude = 10 ** Math.floor(Math.log10(raw))
  const step = ([1, 2, 2.5, 5, 10].find((m) => m * magnitude >= raw) ?? 10) * magnitude
  const top = Math.ceil(Math.max(max, 1) / step) * step
  return Array.from({ length: Math.round(top / step) + 1 }, (_, i) => i * step)
}

/** 왼쪽 눈금 글자와 옅은 기준선 (0은 진한 바닥선) */
function GridLines({ ticks, y, left, right }: { ticks: number[]; y: (v: number) => number; left: number; right: number }) {
  return (
    <>
      {ticks.map((t) => (
        <g key={t}>
          <line x1={left} x2={right} y1={y(t)} y2={y(t)} className={t === 0 ? 'chart-base' : 'chart-grid'} />
          <text x={left - 6} y={y(t) + 3.5} textAnchor="end" className="chart-tick">{compactValue(t)}</text>
        </g>
      ))}
    </>
  )
}

export interface LinePoint {
  label: string
  short: string
  value: number | null
  /** 조건에 포함된 구간이면 true. 아니면 점을 흐리게 그린다. */
  selected: boolean
}

/**
 * 꺾은선 그래프 (단일 계열). 왼쪽에 자동 눈금과 옅은 기준선을 그린다.
 * 값이 없는 구간에서 선을 끊고, 모든 점에 수치를 붙인다(조건 밖 구간은 흐리게).
 */
export function LineChart({ points }: { points: LinePoint[] }) {
  const width = 300
  const height = 156
  const pad = { top: 30, right: 14, bottom: 24, left: 36 }
  const ticks = niceTicks(Math.max(0, ...points.map((p) => p.value ?? 0)), 4)
  const max = ticks[ticks.length - 1]
  // 첫 점·끝 점이 눈금 글자나 오른쪽 끝에 붙지 않도록 그래프 안쪽 여백을 둔다.
  const inset = 14
  const step = (width - pad.left - pad.right - inset * 2) / Math.max(1, points.length - 1)
  const x = (i: number) => pad.left + inset + i * step
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
    <svg className="chart-svg" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="시간대별 인구 꺾은선 그래프">
      <GridLines ticks={ticks} y={y} left={pad.left} right={width - pad.right} />
      {segments.map((d) => <path key={d} d={d} className="line-path" />)}
      {points.map((p, i) => (
        <g key={p.label}>
          {p.value !== null && (
            <>
              <circle cx={x(i)} cy={y(p.value)} r={4} className={`line-dot${p.selected ? '' : ' dim'}`} />
              <text x={x(i)} y={y(p.value) - 10} textAnchor="middle" className={`chart-value${p.selected ? '' : ' dim'}`}>
                {compactValue(p.value)}
              </text>
            </>
          )}
          {/* 마우스오버 영역은 점보다 넓게 */}
          <rect x={x(i) - step / 2} y={pad.top - 10} width={step} height={height - pad.top - pad.bottom + 10} className="chart-hit">
            <title>{`${p.label} ${formatValue(p.value)}`}</title>
          </rect>
          <text x={x(i)} y={height - 6} textAnchor="middle" className="chart-label">{p.short}</text>
        </g>
      ))}
    </svg>
  )
}

export interface ColumnItem {
  label: string
  short: string
  value: number | null
  /** 조건에 포함된 항목이면 true. 아니면 막대를 흐리게 그린다. */
  selected: boolean
}

/**
 * 세로 막대 그래프 (단일 계열). 왼쪽에 자동 눈금 2~3칸과 옅은 기준선을 그린다.
 * 조건에 포함된 항목 중 가장 큰 막대만 진하게 칠하고 수치를 붙인다. 나머지 수치는 막대에 마우스를 올리면 보인다.
 */
export function ColumnChart({ items }: { items: ColumnItem[] }) {
  const width = 300
  const height = 150
  const pad = { top: 24, right: 8, bottom: 22, left: 36 }
  const ticks = niceTicks(Math.max(0, ...items.map((i) => i.value ?? 0)), 3)
  const max = ticks[ticks.length - 1]
  const slot = (width - pad.left - pad.right) / Math.max(1, items.length)
  const barWidth = Math.min(10, slot * 0.6) // 칸 폭과 관계없이 가는 막대
  const cx = (i: number) => pad.left + slot * (i + 0.5)
  const y = (v: number) => pad.top + (1 - v / max) * (height - pad.top - pad.bottom)
  const peak = Math.max(...items.filter((i) => i.selected).map((i) => i.value ?? 0))

  return (
    <svg className="chart-svg" viewBox={`0 0 ${width} ${height}`} role="img" aria-label="월별 인구 세로 막대 그래프">
      <GridLines ticks={ticks} y={y} left={pad.left} right={width - pad.right} />
      {items.map((item, i) => {
        const value = item.value ?? 0
        const isPeak = item.selected && value === peak && peak > 0
        return (
          <g key={item.label} className={`col-g${isPeak ? ' peak' : ''}${item.selected ? '' : ' dim'}`}>
            <rect className="chart-hit" x={cx(i) - slot / 2} y={pad.top - 18} width={slot} height={height - pad.top - pad.bottom + 18}>
              <title>{`${item.label} ${formatValue(item.value)}`}</title>
            </rect>
            {value > 0 && (
              <rect className="col-bar" x={cx(i) - barWidth / 2} y={y(value)} width={barWidth} height={y(0) - y(value)} rx={2} />
            )}
            <text x={cx(i)} y={y(value) - 6} textAnchor="middle" className="chart-value col-value">{compactValue(item.value)}</text>
            <text x={cx(i)} y={height - 6} textAnchor="middle" className="chart-label">{item.short}</text>
          </g>
        )
      })}
    </svg>
  )
}
