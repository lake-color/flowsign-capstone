import type { ComponentProps } from 'react'
import BoardDetail from './BoardDetail'
import './RightPanel.css'

type DetailProps = Omit<ComponentProps<typeof BoardDetail>, 'hidden' | 'onCollapse'>

interface Props extends DetailProps {
  /** 상세 패널이 펼쳐져 있는지. 접으면 오른쪽 가장자리에 '상세' 탭만 남는다. */
  open: boolean
  onToggle: () => void
}

/** 우측 영역. 게시대 상세를 보여주고, 접어서 지도를 넓게 볼 수 있다. */
function RightPanel({ open, onToggle, ...detail }: Props) {
  return (
    <>
      <button type="button" className="right-tab" hidden={open} onClick={onToggle} title="게시대 상세 열기" aria-label="게시대 상세 열기">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M15 6l-6 6 6 6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" fill="none" />
        </svg>
        상세
      </button>
      <BoardDetail {...detail} hidden={!open} onCollapse={onToggle} />
    </>
  )
}

export default RightPanel
