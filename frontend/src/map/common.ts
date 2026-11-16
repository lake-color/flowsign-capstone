import type { Bounds, GridSpec, MapCell, NearbyStore, Purpose } from '../lib/types'

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

/** 주변 상가 점 색 (강조하지 않은 업종) */
export const STORE_COLOR = '#475569'

/** 상세 패널에서 고른 업종의 상가 점 색. 팔레트 포인트 색(--accent)을 따른다. */
export function storeHighlightColor(): string {
  return getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#e5564a'
}

/** 같은 좌표(대개 같은 건물)에 있는 상가 묶음. 지도에는 묶음마다 점 하나를 그린다. */
export interface StoreSpot {
  latitude: number
  longitude: number
  stores: NearbyStore[]
}

/**
 * 상가를 좌표별로 묶는다. 원본은 건물 단위 좌표라 한 건물의 상가들이 같은 점에 겹치므로,
 * 그대로 그리면 패널의 상가 수보다 점이 훨씬 적어 보인다.
 */
export function groupStoresBySpot(stores: NearbyStore[]): StoreSpot[] {
  const spots = new Map<string, StoreSpot>()
  for (const s of stores) {
    const key = `${s.latitude},${s.longitude}`
    const spot = spots.get(key) ?? { latitude: s.latitude, longitude: s.longitude, stores: [] }
    spot.stores.push(s)
    spots.set(key, spot)
  }
  return [...spots.values()]
}

/** 점 반지름(px). 같은 위치의 상가가 많을수록 조금씩 커진다(최대 +6px). */
export function storeSpotRadius(count: number, highlighted: boolean): number {
  return (highlighted ? 5 : 3) + Math.min(6, (Math.sqrt(count) - 1) * 1.8)
}

/** 점 마우스오버 문구 (줄 목록). 같은 위치 상가가 여럿이면 개수와 앞의 8곳을 보여준다. */
export function storeSpotLines(spot: StoreSpot, highlight: string | null): string[] {
  const line = (s: NearbyStore) => `${s.name ?? '상호명 없음'} · ${s.subCategoryName}`
  if (spot.stores.length === 1) return [line(spot.stores[0])]
  // 강조한 업종이 있으면 그 업종을 앞에 둔다.
  const sorted = [...spot.stores].sort((a, b) => Number(b.category === highlight) - Number(a.category === highlight))
  const lines = [`같은 위치 상가 ${spot.stores.length}곳`, ...sorted.slice(0, 8).map(line)]
  if (spot.stores.length > 8) lines.push(`… 외 ${spot.stores.length - 8}곳`)
  return lines
}

/** 툴팁 HTML에 넣을 글자 이스케이프 (상호명은 외부 데이터이므로 HTML로 해석되면 안 된다) */
export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] ?? c)
}

/** 3×3 Cell 색. 팔레트마다 포인트 색과 겹치지 않도록 CSS 변수 --cell(index.css)에서 읽는다. */
export function cellColor(): string {
  return getComputedStyle(document.documentElement).getPropertyValue('--cell').trim() || '#8b5cf6'
}
