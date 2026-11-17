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

/** 인구 조회 실패. code는 명세 5장 오류 코드(DATA_NOT_READY·UNSUPPORTED_FILTER 등), 네트워크 오류 등이면 null */
export interface PopulationError {
  code: string | null
  message: string
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

/** 상가 업종 묶음 (API 명세 4.8) */
export type StoreCategory =
  | 'RESTAURANT' | 'CAFE' | 'BAR' | 'RETAIL' | 'PHARMACY' | 'LIVING_SERVICE' | 'EDUCATION' | 'MEDICAL'
  | 'VETERINARY' | 'STUDY_ROOM' | 'SCIENCE_TECH' | 'ARTS_SPORTS' | 'REAL_ESTATE' | 'FACILITY_RENTAL' | 'LODGING'

/** 게시대 3×3 Cell 안의 상가 한 곳 */
export interface NearbyStore {
  storeId: string
  name: string | null
  category: StoreCategory
  subCategoryName: string
  latitude: number
  longitude: number
}

/** GET /boards/{id}/stores. categories는 많은 순이고 0건 업종은 없다. */
export interface BoardStores {
  baseMonth: string | null
  totalCount: number
  categories: { category: StoreCategory; count: number }[]
  stores: NearbyStore[]
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
  /** 지도 중심을 옮길 위치. 새 객체가 올 때마다 그곳으로 이동한다 (추천 카드 선택 시). */
  focus: { latitude: number; longitude: number } | null
  /** 색상 팔레트 이름. 바뀌면 지도가 색을 다시 읽어 3×3 Cell을 다시 그린다. */
  palette: string
  /** 선택 게시대 3×3 Cell 안의 상가 (레이어를 껐으면 null) */
  stores: NearbyStore[] | null
  /** 상세 패널에서 고른 업종. 이 업종의 상가만 진하게 그린다. */
  highlightCategory: StoreCategory | null
}

/** 추천 결과 한 건 (API 명세 4.6 items 중 화면에 쓰는 필드만) */
export interface RecommendationItem {
  rank: number
  boardId: string
  name: string
  city: string | null
  dong: string | null
  purpose: Purpose
  displayScore: number
  reason: string
  scoreBreakdown: { key: string; label: string; contribution: number }[]
}
