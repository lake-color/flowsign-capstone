import type { Bounds, GridSpec, MapCell, Purpose } from '../lib/types'

/** 격자 한 칸이 화면에서 이 픽셀보다 작으면 격자선을 그리지 않는다. 임시값(명세 9장 미정). */
export const GRID_MIN_PIXELS = 12
/** 한 번에 그릴 격자선 최대 개수 (너무 축소된 화면 보호) */
const GRID_MAX_LINES = 400

export const PURPOSE_COLOR: Record<Purpose, string> = {
  COMMERCIAL: '#2563eb',
  MIXED: '#16a34a',
  ADMINISTRATIVE: '#6b7280',
}

/** 화면 범위 안의 격자선 좌표. 위도선은 [lat, west→east], 경도선은 [lng, south→north]. */
export function gridLines(grid: GridSpec, b: Bounds): { lats: number[]; lngs: number[] } | null {
  const { originLatitude: oLat, originLongitude: oLng, latitudeStepDeg: dLat, longitudeStepDeg: dLng } = grid
  const latFrom = Math.ceil((b.south - oLat) / dLat)
  const latTo = Math.floor((b.north - oLat) / dLat)
  const lngFrom = Math.ceil((b.west - oLng) / dLng)
  const lngTo = Math.floor((b.east - oLng) / dLng)
  if (latTo - latFrom + lngTo - lngFrom > GRID_MAX_LINES) return null

  const lats: number[] = []
  const lngs: number[] = []
  for (let i = latFrom; i <= latTo; i++) lats.push(oLat + i * dLat)
  for (let i = lngFrom; i <= lngTo; i++) lngs.push(oLng + i * dLng)
  return { lats, lngs }
}

/** Cell 중심에서 사각형 변까지의 거리(m). 250m Cell의 반. */
const CELL_HALF_METERS = 125
const METERS_PER_DEG_LAT = 111_320

/**
 * Cell 중심 ±125m 사각형 범위 (변이 정남북·정동서인 근사).
 * Cell 데이터에는 중심 좌표만 있으므로 격자 검증 여부와 관계없이 이 근사로 그린다.
 */
export function cellBounds(cell: MapCell): Bounds {
  const halfLat = CELL_HALF_METERS / METERS_PER_DEG_LAT
  const halfLng = CELL_HALF_METERS / (METERS_PER_DEG_LAT * Math.cos((cell.latitude * Math.PI) / 180))
  return {
    south: cell.latitude - halfLat,
    west: cell.longitude - halfLng,
    north: cell.latitude + halfLat,
    east: cell.longitude + halfLng,
  }
}

/** 통신사 Cell 레이어 선 색 */
export const ALL_CELL_COLOR = '#0ea5e9'

/** 선택 게시대 3×3 Cell의 소속 Cell 강조 색 */
export const CENTER_CELL_COLOR = '#f59e0b'

/** 3×3 Cell 색. 팔레트마다 포인트 색과 겹치지 않도록 CSS 변수 --cell(index.css)에서 읽는다. */
export function cellColor(): string {
  return getComputedStyle(document.documentElement).getPropertyValue('--cell').trim() || '#8b5cf6'
}
