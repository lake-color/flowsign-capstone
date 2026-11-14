import type { PopulationQuery, Purpose } from './types'

// 조건·차트 공통 코드와 표시 이름

export const AGE_LABEL: Record<string, string> = {
  UNDER_20: '20대 미만', AGE_20S: '20대', AGE_30S: '30대', AGE_40S: '40대', AGE_50S: '50대', AGE_60_PLUS: '60대 이상',
  UNKNOWN: '미상',
}
export const TIME_BANDS = ['06_08', '08_10', '10_12', '12_14', '14_16', '16_18', '18_20']
export const timeLabel = (t: string) => `${t.slice(0, 2)}~${t.slice(3)}시`

export const DEFAULT_QUERY: PopulationQuery = {
  gender: 'ALL',
  ageGroups: Object.keys(AGE_LABEL).filter((k) => k !== 'UNKNOWN'),
  flowType: 'ALL',
  timeBands: TIME_BANDS,
  periodKind: 'YEAR',
  season: 'SUMMER',
  month: 1,
  dayType: 'ALL',
}

export const PURPOSE_LABEL: Record<Purpose, string> = {
  COMMERCIAL: '상업용',
  MIXED: '겸용',
  ADMINISTRATIVE: '행정용',
}

export const CITY_LABEL: Record<string, string> = { ANYANG: '안양시', GUNPO: '군포시', SIHEUNG: '시흥시' }

export type Palette = 'coral' | 'teal' | 'indigo'

// 색상 팔레트 목록. color는 선택 점에 쓰는 라이트 모드 포인트 색이고, 실제 색은 index.css의 data-palette 값으로 바뀐다.
export const PALETTES: { key: Palette; label: string; color: string }[] = [
  { key: 'coral', label: 'Coral Warm', color: '#e5564a' },
  { key: 'teal', label: 'Deep Teal', color: '#0e7c86' },
  { key: 'indigo', label: 'Indigo Violet', color: '#5b4bdb' },
]

/** 표시용 숫자 포맷 */
export function formatValue(v: number | null): string {
  return v === null ? '-' : Math.round(v).toLocaleString('ko-KR')
}
