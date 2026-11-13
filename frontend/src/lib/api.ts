import type { Board, BoardPopulation, Bounds, CellItem, MapCell, Meta, PopulationQuery } from './types'

// 로컬 개발은 Vite 프록시(/api → localhost:8080)를 거친다.
const BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
const TIMEOUT_MS = 10_000

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const timeout = AbortSignal.timeout(TIMEOUT_MS)
  const response = await fetch(`${BASE}${path}`, {
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
  })
  const body = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error(body?.error?.message ?? `요청 실패 (${response.status})`)
  }
  return body.data as T
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
  /** 화면 범위 안의 통신사 Cell. 너무 많으면 truncated=true, cells=[] */
  cellsInView: (b: Bounds, signal?: AbortSignal) =>
    get<{ cells: MapCell[]; truncated: boolean; limit: number }>(
      `/cells?${new URLSearchParams({ west: String(b.west), south: String(b.south), east: String(b.east), north: String(b.north) })}`,
      signal,
    ),
}
