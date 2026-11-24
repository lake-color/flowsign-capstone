import { useEffect, useRef, useState } from 'react'
import { DEFAULT_SIZE_WEIGHT, PURPOSE_LABEL } from '../lib/constants'
import type { GridSpec, Purpose } from '../lib/types'
import { PURPOSE_COLOR } from '../map/common'
import './LayerMenu.css'

interface Props {
  /** 250m 격자선. 격자 정보(/meta.grid)가 없으면 켤 수 없다. 검증 전(NOT_VERIFIED)이면 근사로 그린다. */
  showGrid: boolean
  onShowGridChange: (v: boolean) => void
  grid: GridSpec | null
  /** 게시대 Cell: 화면 범위 안 모든 게시대의 3×3 Cell (인구값 없음) */
  showBoardCells: boolean
  onShowBoardCellsChange: (v: boolean) => void
  /** 선택 게시대의 3×3 Cell. 게시대를 고르지 않았으면(hasSelectedBoard=false) 켤 수 없다. */
  showCells: boolean
  onShowCellsChange: (v: boolean) => void
  hasSelectedBoard: boolean
  /** 선택 게시대 3×3 Cell 안의 상가 점. 게시대를 고르지 않았으면 켤 수 없다. */
  showStores: boolean
  onShowStoresChange: (v: boolean) => void
  /** 용도별 게시대 마커 표시 여부 */
  purposeVisible: Record<Purpose, boolean>
  onPurposeVisibleChange: (purpose: Purpose, visible: boolean) => void
  /** 추천 점수에서 유동 규모:타깃 비율 배점 중 유동 규모의 몫(0~1). 슬라이더를 놓았을 때만 바뀐다. */
  sizeWeight: number
  onSizeWeightChange: (w: number) => void
}

const PURPOSES = Object.keys(PURPOSE_LABEL) as Purpose[]

/** 스위치 한 줄. 이름과 짧은 설명을 왼쪽에, 스위치를 오른쪽에 둔다. */
function SwitchRow(props: {
  label: string
  hint?: string
  checked: boolean
  disabled?: boolean
  dot?: { color: string; square: boolean }
  onChange: (v: boolean) => void
}) {
  const { label, hint, checked, disabled, dot, onChange } = props
  return (
    <label className={`layer-row${disabled ? ' disabled' : ''}`}>
      <span className="layer-text">
        <b>
          {dot && <i style={{ background: dot.color, borderRadius: dot.square ? 2 : '50%' }} />}
          {label}
        </b>
        {hint && <small>{hint}</small>}
      </span>
      <input type="checkbox" role="switch" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span className="track" />
    </label>
  )
}

/** 비율 슬라이더 칸 수: 0~10을 0.5씩 (유동 규모 쪽 값) */
const RATIO_MAX = 10
const RATIO_STEP = 0.5
const fmt = (v: number) => (Number.isInteger(v) ? String(v) : v.toFixed(1))

/**
 * 추천 점수 비율 슬라이더. 왼쪽 끝으로 갈수록 유동 규모, 오른쪽 끝으로 갈수록 타깃 비율 쪽이고(라벨 위치와 같음),
 * 두 값의 합은 항상 10이다.
 * 끄는 동안은 숫자만 바꾸고, 놓았을 때(포인터·키보드) 한 번만 알린다.
 */
function ScoreRatio({ value, onChange }: { value: number; onChange: (w: number) => void }) {
  const [draft, setDraft] = useState(value * RATIO_MAX)
  // 밖에서 값이 바뀌면(초기화 등) 맞춘다
  const [prevValue, setPrevValue] = useState(value)
  if (value !== prevValue) {
    setPrevValue(value)
    setDraft(value * RATIO_MAX)
  }
  const commit = () => {
    const w = draft / RATIO_MAX
    if (w !== value) onChange(w)
  }
  const size = draft
  const share = RATIO_MAX - draft
  return (
    <div className="ratio">
      <div className="ratio-labels" aria-hidden="true">
        <span>유동 규모 <b>{fmt(size)}</b></span>
        <span><b>{fmt(share)}</b> 타깃 비율</span>
      </div>
      <input type="range" min={0} max={RATIO_MAX} step={RATIO_STEP} value={RATIO_MAX - draft}
        aria-label="추천 점수 비율 (유동 규모 : 타깃 비율)" aria-valuetext={`유동 규모 ${fmt(size)} : 타깃 비율 ${fmt(share)}`}
        onChange={(e) => setDraft(RATIO_MAX - Number(e.target.value))}
        onPointerUp={commit} onKeyUp={commit} onBlur={commit} />
      <div className="ratio-foot">
        <small>유동 유형을 고르면 80점, '전체'면 100점을 이 비율로 나눕니다.</small>
        {value !== DEFAULT_SIZE_WEIGHT && (
          <button type="button" className="link" onClick={() => onChange(DEFAULT_SIZE_WEIGHT)}>5:5로</button>
        )}
      </div>
    </div>
  )
}

/**
 * 왼쪽 탭 아래의 '설정' 버튼과 팝오버. 지도에 겹쳐 그리는 레이어(격자선·게시대 Cell·3×3 Cell·주변 업종)와
 * 상업용·겸용·행정용 게시대 마커 표시를 켜고 끄며, 맨 아래에서 추천 점수 비율(유동 규모:타깃 비율)을 정한다.
 * 바깥을 누르거나 Esc를 누르면 닫힌다.
 */
function LayerMenu({
  showGrid, onShowGridChange, grid, showBoardCells, onShowBoardCellsChange,
  showCells, onShowCellsChange, hasSelectedBoard, showStores, onShowStoresChange, purposeVisible, onPurposeVisibleChange,
  sizeWeight, onSizeWeightChange,
}: Props) {
  const [open, setOpen] = useState(false)
  const wrapRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const hiddenCount = PURPOSES.filter((p) => !purposeVisible[p]).length
  const gridHint = !grid ? '격자 정보가 없습니다.' : grid.status === 'VERIFIED' ? '화면 범위의 격자선' : '화면 범위의 격자선 (근사)'

  return (
    <div className="layer-wrap" ref={wrapRef}>
      <button type="button" className={`layer-btn${open ? ' active' : ''}`} aria-haspopup="dialog" aria-expanded={open}
        title="지도 표시와 추천 점수 비율 설정" onClick={() => setOpen((v) => !v)}>
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M4 7h10M18 7h2M4 17h4M12 17h8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" fill="none" />
          <circle cx="16" cy="7" r="2" stroke="currentColor" strokeWidth="2" fill="none" />
          <circle cx="10" cy="17" r="2" stroke="currentColor" strokeWidth="2" fill="none" />
        </svg>
        <span className="tab-label">설정</span>
        {hiddenCount > 0 && <span className="layer-badge" aria-label={`${hiddenCount}개 용도 숨김`}>{hiddenCount}</span>}
      </button>

      {open && (
        <div className="layer-pop" role="dialog" aria-label="설정">
          <h3>설정</h3>

          <p className="layer-title">레이어</p>
          <SwitchRow label="250m 격자선" hint={gridHint} checked={showGrid} disabled={!grid} onChange={onShowGridChange} />
          <SwitchRow label="게시대 Cell" hint="화면 범위 모든 게시대의 3×3 Cell"
            checked={showBoardCells} onChange={onShowBoardCellsChange} />
          <SwitchRow label="3×3 Cell" hint={hasSelectedBoard ? '선택한 게시대의 영향권' : '게시대를 먼저 선택하세요.'}
            checked={showCells} disabled={!hasSelectedBoard} onChange={onShowCellsChange} />
          <SwitchRow label="주변 업종" hint={hasSelectedBoard ? '선택한 게시대 3×3 Cell 안의 상가' : '게시대를 먼저 선택하세요.'}
            checked={showStores} disabled={!hasSelectedBoard} onChange={onShowStoresChange} />

          <p className="layer-title">게시대 용도</p>
          {PURPOSES.map((p) => (
            <SwitchRow key={p} label={PURPOSE_LABEL[p]} dot={{ color: PURPOSE_COLOR[p], square: p === 'ADMINISTRATIVE' }}
              checked={purposeVisible[p]} onChange={(v) => onPurposeVisibleChange(p, v)} />
          ))}
          <p className="layer-note">선택한 게시대는 용도를 숨겨도 지도에 표시됩니다. 추천 결과 목록에는 영향이 없습니다.</p>

          <p className="layer-title">추천 점수 비율</p>
          <ScoreRatio value={sizeWeight} onChange={onSizeWeightChange} />
        </div>
      )}
    </div>
  )
}

export default LayerMenu
