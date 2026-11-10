import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { useEffect, useRef } from 'react'
import type { MapProps } from '../types'
import { ALL_CELL_COLOR, GRID_MIN_PIXELS, PURPOSE_COLOR, cellBounds, formatValue, gridLines } from './common'

/**
 * Leaflet 지도 (대안). 키 없이 OpenStreetMap 타일로 동작한다.
 */
function LeafletMap({ boards, selectedBoardId, onSelectBoard, grid, showGrid, cells, allCells, onViewChange }: MapProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<L.Map | null>(null)
  const layers = useRef<{
    markers: L.LayerGroup; grid: L.LayerGroup; cells: L.LayerGroup; allCells: L.LayerGroup; canvas: L.Renderer
  } | null>(null)
  const fitted = useRef(false)

  // 지도 생성 (한 번)
  useEffect(() => {
    if (!containerRef.current) return
    const map = L.map(containerRef.current, { center: [37.39, 126.92], zoom: 13 })
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors',
    }).addTo(map)
    // 추가 순서가 그리는 순서다: 통신사 Cell 전체(맨 아래) → 격자선 → 3×3 → 마커
    layers.current = {
      allCells: L.layerGroup().addTo(map),
      canvas: L.canvas({ padding: 0.5 }), // Cell 수천 개는 SVG보다 canvas가 가볍다
      grid: L.layerGroup().addTo(map),
      cells: L.layerGroup().addTo(map),
      markers: L.layerGroup().addTo(map),
    }
    mapRef.current = map
    // 레이아웃이 바뀌어 컨테이너 크기가 달라지면 Leaflet이 크기를 다시 계산해야 타일·도형이 어긋나지 않는다.
    const resize = new ResizeObserver(() => map.invalidateSize())
    resize.observe(containerRef.current)
    return () => {
      resize.disconnect()
      map.remove()
      mapRef.current = null
      fitted.current = false
    }
  }, [])

  // 지도 이동·확대가 끝나면 화면 범위를 알린다 (통신사 Cell 조회용)
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    const emit = () => {
      const b = map.getBounds()
      onViewChange({ south: b.getSouth(), west: b.getWest(), north: b.getNorth(), east: b.getEast() })
    }
    emit()
    map.on('moveend', emit)
    return () => {
      map.off('moveend', emit)
    }
  }, [onViewChange])

  // 통신사 Cell 전체 (화면 범위만, 인구값 없음)
  useEffect(() => {
    const layer = layers.current
    if (!layer) return
    layer.allCells.clearLayers()
    const style = { renderer: layer.canvas, color: ALL_CELL_COLOR, weight: 1, opacity: 0.7, fillOpacity: 0.06, interactive: false }
    for (const cell of allCells ?? []) {
      const b = cellBounds(cell, grid)
      if (b) L.rectangle([[b.south, b.west], [b.north, b.east]], style).addTo(layer.allCells)
      else L.circleMarker([cell.latitude, cell.longitude], { ...style, radius: 2 }).addTo(layer.allCells)
    }
  }, [allCells, grid])

  // 게시대 마커 (게시대 자체 위경도). 행정용은 회색 사각형.
  useEffect(() => {
    const map = mapRef.current
    const group = layers.current?.markers
    if (!map || !group) return
    group.clearLayers()
    for (const b of boards) {
      const selected = b.boardId === selectedBoardId
      const size = selected ? 18 : 12
      const shape = b.purpose === 'ADMINISTRATIVE' ? '2px' : '50%'
      const icon = L.divIcon({
        className: '',
        iconSize: [size, size],
        html: `<div style="width:${size}px;height:${size}px;border-radius:${shape};background:${PURPOSE_COLOR[b.purpose]};border:2px solid ${selected ? '#f59e0b' : '#fff'};box-shadow:0 0 2px #0008"></div>`,
      })
      L.marker([b.latitude, b.longitude], { icon, title: b.name })
        .on('click', () => onSelectBoard(b.boardId))
        .addTo(group)
    }
    if (!fitted.current && boards.length > 0) {
      map.fitBounds(L.latLngBounds(boards.map((b) => [b.latitude, b.longitude])), { padding: [30, 30] })
      fitted.current = true
    }
  }, [boards, selectedBoardId, onSelectBoard])

  // 250m 격자선: 화면 범위만 계산해 그린다. 칸이 GRID_MIN_PIXELS보다 작으면 생략.
  useEffect(() => {
    const map = mapRef.current
    const group = layers.current?.grid
    if (!map || !group) return
    const draw = () => {
      group.clearLayers()
      if (!showGrid || !grid || grid.status !== 'VERIFIED') return
      const c = map.getCenter()
      const px = map.latLngToContainerPoint([c.lat + grid.latitudeStepDeg, c.lng]).distanceTo(map.latLngToContainerPoint(c))
      if (px < GRID_MIN_PIXELS) return
      const b = map.getBounds()
      const lines = gridLines(grid, { south: b.getSouth(), west: b.getWest(), north: b.getNorth(), east: b.getEast() })
      if (!lines) return
      const style = { color: '#ef4444', weight: 1, opacity: 0.45, interactive: false }
      for (const lat of lines.lats) L.polyline([[lat, b.getWest()], [lat, b.getEast()]], style).addTo(group)
      for (const lng of lines.lngs) L.polyline([[b.getSouth(), lng], [b.getNorth(), lng]], style).addTo(group)
    }
    draw()
    map.on('moveend', draw)
    return () => {
      map.off('moveend', draw)
    }
  }, [grid, showGrid])

  // 선택 게시대의 3×3 Cell
  useEffect(() => {
    const group = layers.current?.cells
    if (!group) return
    group.clearLayers()
    for (const cell of cells ?? []) {
      const label = `${cell.isCenter ? '소속 Cell ' : ''}${cell.cellId}<br>인구 ${formatValue(cell.population.value)}`
      const style = { color: cell.isCenter ? '#f59e0b' : '#8b5cf6', weight: 2, fillOpacity: 0.15 }
      if (cell.geometry) {
        const ring = cell.geometry.coordinates[0].map(([lng, lat]) => [lat, lng] as [number, number])
        L.polygon(ring, style).bindTooltip(label).addTo(group)
      } else {
        L.circleMarker([cell.center.latitude, cell.center.longitude], { ...style, radius: 6 }).bindTooltip(label).addTo(group)
      }
    }
  }, [cells])

  return <div ref={containerRef} className="map-canvas" />
}

export default LeafletMap
