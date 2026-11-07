import type { PopulationQuery } from './types'

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
