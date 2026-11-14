import './SideTabs.css'

export type LeftTab = 'filter' | 'result'

interface Props {
  /** 열려 있는 패널. 둘 다 닫혀 있으면 null */
  tab: LeftTab | null
  /** 탭 클릭. 열려 있는 탭을 다시 누르면 패널을 닫는다(부모가 처리). */
  onChange: (tab: LeftTab) => void
}

/** 왼쪽 가장자리의 세로 탭. 옆 패널에 조건 설정과 추천 결과 중 무엇을 보여줄지 고른다. 선택된 탭을 다시 누르면 패널이 닫힌다. */
function SideTabs({ tab, onChange }: Props) {
  return (
    <nav className="side-tabs" role="tablist" aria-label="왼쪽 패널" aria-orientation="vertical">
      <button type="button" role="tab" aria-selected={tab === 'filter'} className={tab === 'filter' ? 'active' : ''} onClick={() => onChange('filter')}>
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M4 7h10M18 7h2M4 17h2M10 17h10" stroke="currentColor" strokeWidth="2" strokeLinecap="round" fill="none" />
          <circle cx="16" cy="7" r="2.2" stroke="currentColor" strokeWidth="2" fill="none" />
          <circle cx="8" cy="17" r="2.2" stroke="currentColor" strokeWidth="2" fill="none" />
        </svg>
        조건 설정
      </button>
      <button type="button" role="tab" aria-selected={tab === 'result'} className={tab === 'result' ? 'active' : ''} onClick={() => onChange('result')}>
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M9 7h11M9 12h11M9 17h11" stroke="currentColor" strokeWidth="2" strokeLinecap="round" fill="none" />
          <circle cx="4.5" cy="7" r="1.3" fill="currentColor" />
          <circle cx="4.5" cy="12" r="1.3" fill="currentColor" />
          <circle cx="4.5" cy="17" r="1.3" fill="currentColor" />
        </svg>
        추천 결과
      </button>
    </nav>
  )
}

export default SideTabs
