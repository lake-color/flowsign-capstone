import type { ComponentProps } from 'react'
import BoardDetail from './BoardDetail'
import './RightPanel.css'

/** 우측 영역. 지금은 게시대 상세만 그대로 그린다. */
function RightPanel(props: ComponentProps<typeof BoardDetail>) {
  return <BoardDetail {...props} />
}

export default RightPanel
