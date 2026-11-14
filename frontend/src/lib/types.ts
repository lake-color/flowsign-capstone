// API 명세 v0.2.1 응답 타입 (데모에서 쓰는 필드만)

export type Purpose = 'COMMERCIAL' | 'MIXED' | 'ADMINISTRATIVE'

export interface PopulationValue {
  value: number | null
  status: 'AVAILABLE' | 'PARTIAL' | 'MISSING' | 'MASKED' | 'UNVERIFIED'
  unitLabel: string | null
}

export interface GridSpec {
  cellSizeMeters: number
  originLatitude: number
  originLongitude: number
  latitudeStepDeg: number
  longitudeStepDeg: number
  status: 'VERIFIED' | 'NOT_VERIFIED'
}

export interface Meta {
  timeBands: string[]
  availableDayTypes: string[]
  grid: GridSpec | null
  notice: string
}

export interface Board {
  boardId: string
  name: string
  city: string | null
  dong: string | null
  address: string | null
  latitude: number
  longitude: number
  purpose: Purpose
  faceCount: number | null
  postingFee: number | null
  canInquire: boolean
  hasPopulationData: boolean
}

export interface DistributionPoint {
  key: string
  population: PopulationValue
  ratio: number | null
}

export interface BoardPopulation {
  centerCellId: string | null
  population: { totalFlow: PopulationValue; stay: PopulationValue; walk: PopulationValue; drive: PopulationValue }
  hourly: { timeBand: string; value: PopulationValue; selected: boolean }[]
  monthly: { month: number; value: PopulationValue; selected: boolean }[]
  genderDistribution: DistributionPoint[]
  ageDistribution: DistributionPoint[]
  servicePopulation: DistributionPoint[]
  expectedCellCount: number
  matchedCellCount: number
  dataNotice: string
}

export interface CellItem {
  cellId: string
  isCenter: boolean
  distanceMeters: number
  center: { latitude: number; longitude: number }
  geometry: { type: 'Polygon'; coordinates: number[][][] } | null
  population: PopulationValue
}

/** 지도 Cell 레이어용 통신사 Cell 중심 (GET /cells, 인구값 없음) */
export interface MapCell {
  cellId: string
  latitude: number
  longitude: number
}

/** 지도 화면 범위 (위경도) */
export interface Bounds {
  south: number
  west: number
  north: number
  east: number
}

/** 상세 인구 조회 조건 (API 명세 4.4 쿼리) */
export interface PopulationQuery {
  gender: 'ALL' | 'MALE' | 'FEMALE'
  ageGroups: string[]
  flowType: 'ALL' | 'STAY' | 'WALK' | 'DRIVE'
  timeBands: string[]
  periodKind: 'YEAR' | 'SEASON' | 'MONTH'
  season: 'SPRING' | 'SUMMER' | 'AUTUMN' | 'WINTER'
  month: number
  dayType: 'ALL' | 'WEEKDAY' | 'WEEKEND'
}

/** 지도 구현(Naver / Leaflet)이 공통으로 받는 값 */
export interface MapProps {
  boards: Board[]
  selectedBoardId: string | null
  onSelectBoard: (boardId: string) => void
  grid: GridSpec | null
  showGrid: boolean
  cells: CellItem[] | null
  /** 화면 범위 안의 통신사 Cell 전체 (레이어를 껐으면 null) */
  allCells: MapCell[] | null
  /** 지도 이동·확대가 끝날 때마다 화면 범위를 알린다 */
  onViewChange: (bounds: Bounds) => void
  /** 색상 팔레트 이름. 바뀌면 지도가 색을 다시 읽어 3×3 Cell을 다시 그린다. */
  palette: string
}
