import { useEffect, useRef, useState } from 'react'
import { formatValue } from '../lib/constants'
import type { MapProps } from '../lib/types'
import { ALL_CELL_COLOR, CENTER_CELL_COLOR, GRID_MIN_PIXELS, PURPOSE_COLOR, cellBounds, cellColor, gridLines } from './common'

/**
 * Naver Maps JS v3 지도 (1순위). VITE_NAVER_MAP_CLIENT_ID가 있어야 동작한다.
 * SDK 타입 패키지를 추가하지 않고 필요한 부분만 any로 다룬다(데모 초안).
 */

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Naver = any

const CLIENT_ID = import.meta.env.VITE_NAVER_MAP_CLIENT_ID as string | undefined

let sdkPromise: Promise<Naver> | null = null

/** SDK 스크립트를 한 번만 불러온다. 2025년 이후 NCP 키는 ncpKeyId 파라미터를 쓴다. */
function loadSdk(): Promise<Naver> {
  const w = window as unknown as { naver?: Naver }
  if (w.naver?.maps) return Promise.resolve(w.naver)
  if (!sdkPromise) {
    sdkPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = `https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=${encodeURIComponent(CLIENT_ID ?? '')}`
      script.async = true
      script.onload = () => (w.naver?.maps ? resolve(w.naver) : reject(new Error('Naver Maps SDK 초기화 실패')))
      script.onerror = () => reject(new Error('Naver Maps SDK를 불러오지 못했습니다.'))
      document.head.appendChild(script)
    })
  }
  return sdkPromise
}

function NaverMap({ boards, selectedBoardId, onSelectBoard, grid, showGrid, cells, allCells, onViewChange, focus, palette }: MapProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [naver, setNaver] = useState<Naver | null>(null)
  const [error, setError] = useState<string | null>(CLIENT_ID ? null : 'VITE_NAVER_MAP_CLIENT_ID가 설정되지 않았습니다.')
  const mapRef = useRef<Naver>(null)
  const overlays = useRef<{ markers: Naver[]; grid: Naver[]; cells: Naver[]; allCells: Naver[] }>({
    markers: [], grid: [], cells: [], allCells: [],
  })
  const fitted = useRef(false)

  // SDK 로드 + 지도 생성
  useEffect(() => {
    if (!CLIENT_ID) return
    const ctrl = new AbortController()
    loadSdk()
      .then((n) => {
        if (ctrl.signal.aborted || !containerRef.current) return
        mapRef.current = new n.maps.Map(containerRef.current, {
          center: new n.maps.LatLng(37.39, 126.92),
          zoom: 13,
        })
        setNaver(n)
      })
      .catch((e: Error) => setError(e.message))
    return () => {
      ctrl.abort()
      mapRef.current?.destroy?.()
      mapRef.current = null
      fitted.current = false
    }
  }, [])

  const clear = (key: 'markers' | 'grid' | 'cells' | 'allCells') => {
    overlays.current[key].forEach((o) => o.setMap(null))
    overlays.current[key] = []
  }

  // 지도 이동·확대가 끝나면(idle) 화면 범위를 알린다 (통신사 Cell 조회용)
  useEffect(() => {
    const map = mapRef.current
    if (!naver || !map) return
    const emit = () => {
      const b = map.getBounds()
      onViewChange({ south: b.getSW().lat(), west: b.getSW().lng(), north: b.getNE().lat(), east: b.getNE().lng() })
    }
    emit()
    const listener = naver.maps.Event.addListener(map, 'idle', emit)
    return () => naver.maps.Event.removeListener(listener)
  }, [naver, onViewChange])

  // 통신사 Cell 전체 (화면 범위만, 인구값 없음). 다른 레이어보다 아래(zIndex)에 둔다.
  useEffect(() => {
    const map = mapRef.current
    if (!naver || !map) return
    clear('allCells')
    const style = { map, strokeColor: ALL_CELL_COLOR, strokeWeight: 1, strokeOpacity: 0.7, fillColor: ALL_CELL_COLOR,
      fillOpacity: 0.06, clickable: false, zIndex: 0 }
    for (const cell of allCells ?? []) {
      const b = cellBounds(cell)
      overlays.current.allCells.push(new naver.maps.Rectangle({ ...style, bounds: new naver.maps.LatLngBounds(
        new naver.maps.LatLng(b.south, b.west), new naver.maps.LatLng(b.north, b.east)) }))
    }
  }, [naver, allCells])

  // 게시대 마커 (게시대 자체 위경도). 행정용은 회색 사각형.
  useEffect(() => {
    const map = mapRef.current
    if (!naver || !map) return
    clear('markers')
    for (const b of boards) {
      const selected = b.boardId === selectedBoardId
      const size = selected ? 18 : 12
      const shape = b.purpose === 'ADMINISTRATIVE' ? '2px' : '50%'
      const marker = new naver.maps.Marker({
        map,
        position: new naver.maps.LatLng(b.latitude, b.longitude),
        title: b.name,
        icon: {
          content: `<div style="width:${size}px;height:${size}px;border-radius:${shape};background:${PURPOSE_COLOR[b.purpose]};border:2px solid ${selected ? '#f59e0b' : '#fff'};box-shadow:0 0 2px #0008"></div>`,
          anchor: new naver.maps.Point(size / 2, size / 2),
        },
      })
      naver.maps.Event.addListener(marker, 'click', () => onSelectBoard(b.boardId))
      overlays.current.markers.push(marker)
    }
    if (!fitted.current && boards.length > 0) {
      const bounds = new naver.maps.LatLngBounds()
      boards.forEach((b) => bounds.extend(new naver.maps.LatLng(b.latitude, b.longitude)))
      map.fitBounds(bounds)
      fitted.current = true
    }
  }, [naver, boards, selectedBoardId, onSelectBoard])

  // 추천 카드에서 고른 게시대로 지도 중심을 옮긴다.
  useEffect(() => {
    if (naver && focus) mapRef.current?.panTo(new naver.maps.LatLng(focus.latitude, focus.longitude))
  }, [naver, focus])

  // 250m 격자선
  useEffect(() => {
    const map = mapRef.current
    if (!naver || !map) return
    const draw = () => {
      clear('grid')
      if (!showGrid || !grid || grid.status !== 'VERIFIED') return
      const proj = map.getProjection()
      const c = map.getCenter()
      const p1 = proj.fromCoordToOffset(c)
      const p2 = proj.fromCoordToOffset(new naver.maps.LatLng(c.lat() + grid.latitudeStepDeg, c.lng()))
      if (Math.abs(p2.y - p1.y) < GRID_MIN_PIXELS) return
      const b = map.getBounds()
      const sw = b.getSW()
      const ne = b.getNE()
      const lines = gridLines(grid, { south: sw.lat(), west: sw.lng(), north: ne.lat(), east: ne.lng() })
      if (!lines) return
      const style = { map, strokeColor: '#6b7280', strokeWeight: 1, strokeOpacity: 0.45, clickable: false }
      for (const lat of lines.lats) {
        overlays.current.grid.push(new naver.maps.Polyline({
          ...style, path: [new naver.maps.LatLng(lat, sw.lng()), new naver.maps.LatLng(lat, ne.lng())],
        }))
      }
      for (const lng of lines.lngs) {
        overlays.current.grid.push(new naver.maps.Polyline({
          ...style, path: [new naver.maps.LatLng(sw.lat(), lng), new naver.maps.LatLng(ne.lat(), lng)],
        }))
      }
    }
    draw()
    const listener = naver.maps.Event.addListener(map, 'idle', draw)
    return () => naver.maps.Event.removeListener(listener)
  }, [naver, grid, showGrid])

  // 선택 게시대의 3×3 Cell
  useEffect(() => {
    const map = mapRef.current
    if (!naver || !map) return
    clear('cells')
    for (const cell of cells ?? []) {
      const color = cell.isCenter ? CENTER_CELL_COLOR : cellColor()
      const title = `${cell.isCenter ? '소속 Cell ' : ''}${cell.cellId} / 인구 ${formatValue(cell.population.value)}`
      if (!cell.geometry) continue // 서버는 항상 사각형을 준다(명세 4.5). 계약상 null이면 그리지 않음
      const overlay = new naver.maps.Polygon({
        map,
        paths: [cell.geometry.coordinates[0].map(([lng, lat]) => new naver.maps.LatLng(lat, lng))],
        strokeColor: color, strokeWeight: 2, fillColor: color, fillOpacity: 0.15,
      })
      naver.maps.Event.addListener(overlay, 'click', () => window.alert(title))
      overlays.current.cells.push(overlay)
    }
  }, [naver, cells, palette])

  return (
    <div className="map-canvas">
      <div ref={containerRef} style={{ width: '100%', height: '100%' }} />
      {error && <div className="map-error">Naver 지도: {error} 상단에서 Leaflet으로 전환할 수 있습니다.</div>}
    </div>
  )
}

export default NaverMap
