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
  /** 통신사 Cell 레이어 안내 문구 (예: 범위가 넓어 확대 필요) */
  allCellsNotice: string | null
}

/**
 * 지도 영역. Naver Maps(1순위)와 Leaflet(대안)을 같은 props로 바꿔 끼운다.
 * 250m 격자선, 통신사 Cell 전체, 선택 게시대 3×3 Cell과 용도별 마커 표시는 왼쪽 탭 아래의 '가시성' 메뉴(LayerMenu)에서 켜고 끈다.
 * 지도 전체 인구 표시는 범위에서 제외되어 있다.
 * 지도 방식 전환·범례는 지도 위에 떠 있고, 좌우 패널은 각자의 CSS에서 지도 위에 겹쳐 배치한다.
 */
function MapView({ provider, onProviderChange, allCellsNotice, ...mapProps }: Props) {
  return (
    <section className="map-view">
      {provider === 'naver' ? <NaverMap {...mapProps} /> : <LeafletMap {...mapProps} />}

      <div className="map-toolbar">
        <div className="provider-seg">
          {(['naver', 'leaflet'] as const).map((p) => (
            <button key={p} type="button" className={provider === p ? 'active' : ''} onClick={() => onProviderChange(p)}>
              {p === 'naver' ? 'Naver' : 'Leaflet'}
            </button>
          ))}
        </div>
      </div>

      {allCellsNotice && <div className="map-notice">{allCellsNotice}</div>}

      <div className="legend">
        {(Object.keys(PURPOSE_LABEL) as Purpose[]).map((p) => (
          <span key={p}><i style={{ background: PURPOSE_COLOR[p], borderRadius: p === 'ADMINISTRATIVE' ? 2 : '50%' }} />{PURPOSE_LABEL[p]}</span>
        ))}
      </div>
    </section>
  )
}

export default MapView
