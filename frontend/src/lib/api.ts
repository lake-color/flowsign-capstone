import type {
  Board, BoardPopulation, BoardStores, Bounds, CellItem, MapCell, Meta, PopulationQuery, RecommendationResult,
} from './types'

// 로컬 개발은 Vite 프록시(/api → localhost:8080)를 거친다.
const BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
const TIMEOUT_MS = 10_000

/** 서버 오류 응답. code는 명세 5장의 오류 코드(DATA_NOT_READY 등)이고, 응답 본문이 없으면 null이다. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string | null

  constructor(message: string, status: number, code: string | null) {
    super(message)
    this.status = status
    this.code = code
  }
}

async function request<T>(path: string, signal?: AbortSignal, init?: RequestInit): Promise<T> {
  const timeout = AbortSignal.timeout(TIMEOUT_MS)
  const response = await fetch(`${BASE}${path}`, {
    ...init,
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
  })
  const body = await response.json().catch(() => null)
  if (!response.ok) {
    throw new ApiError(body?.error?.message ?? `요청 실패 (${response.status})`, response.status, body?.error?.code ?? null)
  }
  return body.data as T
}

const get = <T>(path: string, signal?: AbortSignal) => request<T>(path, signal)

const post = <T>(path: string, payload: unknown, signal?: AbortSignal) =>
  request<T>(path, signal, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })

// 조건 패널에 지역·게시대 용도 선택이 아직 없어 명세 2.1 기본 조건(3개 시, 상업용·겸용)으로 추천한다.
const RECOMMEND_CITIES = ['ANYANG', 'GUNPO', 'SIHEUNG']
const RECOMMEND_PURPOSES = ['COMMERCIAL', 'MIXED']
const RECOMMEND_PAGE_SIZE = 10

/** 조회 조건 → 추천 요청 본문 criteria (명세 2.1 Criteria) */
function toCriteria(q: PopulationQuery) {
  return {
    cities: RECOMMEND_CITIES,
    gender: q.gender,
    ageGroups: q.ageGroups,
    flowType: q.flowType,
    timeBands: q.timeBands,
    period: {
      kind: q.periodKind,
      year: 2025,
      ...(q.periodKind === 'SEASON' ? { season: q.season } : {}),
      ...(q.periodKind === 'MONTH' ? { month: q.month } : {}),
    },
    dayType: q.dayType,
    purposes: RECOMMEND_PURPOSES,
  }
}

function toQuery(q: PopulationQuery): string {
  const params = new URLSearchParams({
    gender: q.gender,
    ageGroups: q.ageGroups.join(','),
    flowType: q.flowType,
    timeBands: q.timeBands.join(','),
    periodKind: q.periodKind,
    dayType: q.dayType,
  })
  if (q.periodKind === 'SEASON') params.set('season', q.season)
  if (q.periodKind === 'MONTH') params.set('month', String(q.month))
  return params.toString()
}

export const api = {
  meta: (signal?: AbortSignal) => get<Meta>('/meta', signal),
  boards: (signal?: AbortSignal) => get<{ boards: Board[] }>('/boards', signal).then((d) => d.boards),
  board: (id: string, signal?: AbortSignal) => get<Board>(`/boards/${id}`, signal),
  population: (id: string, q: PopulationQuery, signal?: AbortSignal) =>
    get<BoardPopulation>(`/boards/${id}/population?${toQuery(q)}`, signal),
  cells: (id: string, q: PopulationQuery, signal?: AbortSignal) =>
    get<{ cells: CellItem[] }>(`/boards/${id}/cells?${toQuery(q)}`, signal).then((d) => d.cells),
  /** 3×3 Cell 안의 상가와 업종별 개수 (인구 조건과 무관) */
  stores: (id: string, signal?: AbortSignal) => get<BoardStores>(`/boards/${id}/stores`, signal),
  /** 화면 범위 안의 통신사 Cell. 너무 많으면 truncated=true, cells=[] */
  cellsInView: (b: Bounds, signal?: AbortSignal) =>
    get<{ cells: MapCell[]; truncated: boolean; limit: number }>(
      `/cells?${new URLSearchParams({ west: String(b.west), south: String(b.south), east: String(b.east), north: String(b.north) })}`,
      signal,
    ),
  /** 화면 범위 안에서 어느 게시대든 그 3×3에 속한 Cell. 응답 모양은 cellsInView와 같다 */
  boardCellsInView: (b: Bounds, signal?: AbortSignal) =>
    get<{ cells: MapCell[]; truncated: boolean; limit: number }>(
      `/board-cells?${new URLSearchParams({ west: String(b.west), south: String(b.south), east: String(b.east), north: String(b.north) })}`,
      signal,
    ),
  /** 추천 결과 한 페이지(10개). page는 0부터 */
  recommend: (q: PopulationQuery, page: number, signal?: AbortSignal) =>
    post<RecommendationResult>('/recommendations/search', { criteria: toCriteria(q), page, pageSize: RECOMMEND_PAGE_SIZE }, signal),
}
