import type { GridSpec, Purpose } from '../types'

/** 격자 한 칸이 화면에서 이 픽셀보다 작으면 격자선을 그리지 않는다. 임시값(명세 9장 미정). */
export const GRID_MIN_PIXELS = 12
/** 한 번에 그릴 격자선 최대 개수 (너무 축소된 화면 보호) */
const GRID_MAX_LINES = 400

export const PURPOSE_COLOR: Record<Purpose, string> = {
  COMMERCIAL: '#2563eb',
  MIXED: '#16a34a',
  ADMINISTRATIVE: '#6b7280',
}

export const PURPOSE_LABEL: Record<Purpose, string> = {
  COMMERCIAL: '상업용',
  MIXED: '겸용',
  ADMINISTRATIVE: '행정용',
}

export interface Bounds {
  south: number
  west: number
  north: number
  east: number
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

/** 표시용 숫자 포맷 */
export function formatValue(v: number | null): string {
  return v === null ? '-' : Math.round(v).toLocaleString('ko-KR')
}
