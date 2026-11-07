import LeafletMap from '../map/LeafletMap'
import NaverMap from '../map/NaverMap'
import { PURPOSE_COLOR, PURPOSE_LABEL } from '../map/common'
import type { MapProps, Purpose } from '../types'

export type MapProvider = 'naver' | 'leaflet'

interface Props extends MapProps {
  provider: MapProvider
  onProviderChange: (p: MapProvider) => void
  onShowGridChange: (v: boolean) => void
  showCells: boolean
  onShowCellsChange: (v: boolean) => void
}

/**
 * 지도 영역. Naver Maps(1순위)와 Leaflet(대안)을 같은 props로 바꿔 끼운다.
 * 250m 격자선(체크박스, /meta.grid로 화면 범위만 계산)과 선택 게시대 3×3 Cell 레이어를 켜고 끈다.
 * 지도 전체 인구 표시는 범위에서 제외되어 있다.
 */
function MapView({ provider, onProviderChange, onShowGridChange, showCells, onShowCellsChange, ...mapProps }: Props) {
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
          <input type="checkbox" disabled={!mapProps.selectedBoardId} checked={showCells}
            onChange={(e) => onShowCellsChange(e.target.checked)} /> 3×3 Cell 보기
        </label>
        <div className="legend">
          {(Object.keys(PURPOSE_LABEL) as Purpose[]).map((p) => (
            <span key={p}><i style={{ background: PURPOSE_COLOR[p], borderRadius: p === 'ADMINISTRATIVE' ? 2 : '50%' }} />{PURPOSE_LABEL[p]}</span>
          ))}
        </div>
      </div>
      {provider === 'naver' ? <NaverMap {...mapProps} /> : <LeafletMap {...mapProps} />}
    </section>
  )
}

export default MapView
