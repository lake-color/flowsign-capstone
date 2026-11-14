import LeafletMap from './LeafletMap'
import NaverMap from './NaverMap'
import { PURPOSE_LABEL } from '../lib/constants'
import type { MapProps, Purpose } from '../lib/types'
import { PURPOSE_COLOR } from './common'
import './MapView.css'

export type MapProvider = 'naver' | 'leaflet'

interface Props extends MapProps {
  provider: MapProvider
  onProviderChange: (p: MapProvider) => void
  onShowGridChange: (v: boolean) => void
  showCells: boolean
  onShowCellsChange: (v: boolean) => void
  showAllCells: boolean
  onShowAllCellsChange: (v: boolean) => void
  /** 통신사 Cell 레이어 안내 문구 (예: 범위가 넓어 확대 필요) */
  allCellsNotice: string | null
}

/**
 * 지도 영역. Naver Maps(1순위)와 Leaflet(대안)을 같은 props로 바꿔 끼운다.
 * 250m 격자선(/meta.grid로 화면 범위만 계산), 통신사 Cell 전체(화면 범위만 /cells로 조회, 인구값 없음),
 * 선택 게시대 3×3 Cell(인구 포함) 레이어를 켜고 끈다. 지도 전체 인구 표시는 범위에서 제외되어 있다.
 */
function MapView({
  provider, onProviderChange, onShowGridChange, showCells, onShowCellsChange,
  showAllCells, onShowAllCellsChange, allCellsNotice, ...mapProps
}: Props) {
  const gridReady = mapProps.grid?.status === 'VERIFIED'
  return (
    <section className="panel map-view">
      <div className="map-toolbar">
        <div className="segmented">
          {(['naver', 'leaflet'] as const).map((p) => (
            <button key={p} className={provider === p ? 'active' : ''} onClick={() => onProviderChange(p)}>
              {p === 'naver' ? 'Naver' : 'Leaflet'}
            </button>
          ))}
        </div>
        <label title={gridReady ? '' : '격자 검증 전에는 사용할 수 없습니다.'}>
          <input type="checkbox" disabled={!gridReady} checked={mapProps.showGrid}
            onChange={(e) => onShowGridChange(e.target.checked)} /> 250m 격자선
        </label>
        <label>
          <input type="checkbox" checked={showAllCells}
            onChange={(e) => onShowAllCellsChange(e.target.checked)} /> 통신사 Cell
        </label>
        <label>
          <input type="checkbox" disabled={!mapProps.selectedBoardId} checked={showCells}
            onChange={(e) => onShowCellsChange(e.target.checked)} /> 3×3 Cell 보기
        </label>
        <div className="legend">
          {(Object.keys(PURPOSE_LABEL) as Purpose[]).map((p) => (
            <span key={p}><i style={{ background: PURPOSE_COLOR[p], borderRadius: p === 'ADMINISTRATIVE' ? 2 : '50%' }} />{PURPOSE_LABEL[p]}</span>
          ))}
        </div>
      </div>
      {allCellsNotice && <div className="map-notice">{allCellsNotice}</div>}
      {provider === 'naver' ? <NaverMap {...mapProps} /> : <LeafletMap {...mapProps} />}
    </section>
  )
}

export default MapView
