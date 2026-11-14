import { useEffect, useRef, useState } from 'react'
import { PURPOSE_LABEL } from '../lib/constants'
import type { Purpose } from '../lib/types'
import { PURPOSE_COLOR } from '../map/common'
import './LayerMenu.css'

interface Props {
  /** 250m 격자선. 격자 검증이 끝나지 않았으면(gridReady=false) 켤 수 없다. */
  showGrid: boolean
  onShowGridChange: (v: boolean) => void
  gridReady: boolean
  /** 통신사 Cell 전체 (화면 범위, 인구값 없음) */
  showAllCells: boolean
  onShowAllCellsChange: (v: boolean) => void
  /** 선택 게시대의 3×3 Cell. 게시대를 고르지 않았으면(hasSelectedBoard=false) 켤 수 없다. */
  showCells: boolean
  onShowCellsChange: (v: boolean) => void
  hasSelectedBoard: boolean
  /** 용도별 게시대 마커 표시 여부 */
  purposeVisible: Record<Purpose, boolean>
  onPurposeVisibleChange: (purpose: Purpose, visible: boolean) => void
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

/**
 * 왼쪽 탭 아래의 '가시성' 버튼과 팝오버. 지도에 겹쳐 그리는 레이어(격자선·통신사 Cell·3×3 Cell)와
 * 상업용·겸용·행정용 게시대 마커 표시를 켜고 끈다. 바깥을 누르거나 Esc를 누르면 닫힌다.
 */
function LayerMenu({
  showGrid, onShowGridChange, gridReady, showAllCells, onShowAllCellsChange,
  showCells, onShowCellsChange, hasSelectedBoard, purposeVisible, onPurposeVisibleChange,
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

  return (
    <div className="layer-wrap" ref={wrapRef}>
      <button type="button" className={`layer-btn${open ? ' active' : ''}`} aria-haspopup="dialog" aria-expanded={open}
        title="지도에 표시할 항목 고르기" onClick={() => setOpen((v) => !v)}>
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" fill="none" />
          <circle cx="12" cy="12" r="3" stroke="currentColor" strokeWidth="2" fill="none" />
        </svg>
        <span className="tab-label">가시성</span>
        {hiddenCount > 0 && <span className="layer-badge" aria-label={`${hiddenCount}개 용도 숨김`}>{hiddenCount}</span>}
      </button>

      {open && (
        <div className="layer-pop" role="dialog" aria-label="지도 가시성">
          <h3>지도 가시성</h3>

          <p className="layer-title">레이어</p>
          <SwitchRow label="250m 격자선" hint={gridReady ? '화면 범위의 격자선' : '격자 검증 전에는 사용할 수 없습니다.'}
            checked={showGrid} disabled={!gridReady} onChange={onShowGridChange} />
          <SwitchRow label="통신사 Cell" hint="화면 범위의 Cell 전체"
            checked={showAllCells} onChange={onShowAllCellsChange} />
          <SwitchRow label="3×3 Cell" hint={hasSelectedBoard ? '선택한 게시대의 영향권' : '게시대를 먼저 선택하세요.'}
            checked={showCells} disabled={!hasSelectedBoard} onChange={onShowCellsChange} />

          <p className="layer-title">게시대 용도</p>
          {PURPOSES.map((p) => (
            <SwitchRow key={p} label={PURPOSE_LABEL[p]} dot={{ color: PURPOSE_COLOR[p], square: p === 'ADMINISTRATIVE' }}
              checked={purposeVisible[p]} onChange={(v) => onPurposeVisibleChange(p, v)} />
          ))}
          <p className="layer-note">선택한 게시대는 용도를 숨겨도 지도에 표시됩니다. 추천 결과 목록에는 영향이 없습니다.</p>
        </div>
      )}
    </div>
  )
}

export default LayerMenu
