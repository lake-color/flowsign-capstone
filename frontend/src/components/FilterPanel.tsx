import { useState } from 'react'
import { AGE_LABEL, DEFAULT_QUERY, TIME_BANDS } from '../lib/constants'
import type { PopulationQuery } from '../lib/types'
import './FilterPanel.css'

interface Props {
  applied: PopulationQuery
  onApply: (q: PopulationQuery) => void
}

const GENDER_OPTIONS = [['ALL', '전체'], ['MALE', '남성'], ['FEMALE', '여성']] as const
const FLOW_OPTIONS = [['ALL', '전체'], ['STAY', '체류'], ['WALK', '보행'], ['DRIVE', '주행']] as const
const PERIOD_OPTIONS = [['YEAR', '연간'], ['SEASON', '계절'], ['MONTH', '월']] as const
const SEASON_OPTIONS = [
  ['SPRING', '봄', '3~5월'], ['SUMMER', '여름', '6~8월'], ['AUTUMN', '가을', '9~11월'], ['WINTER', '겨울', '12·1·2월'],
] as const
const DAY_OPTIONS = [['ALL', '전체'], ['WEEKDAY', '주중'], ['WEEKEND', '주말']] as const
// 칩 폭이 좁아 '시'를 뺀 짧은 표시를 쓴다. (06_08 → 06~08)
const shortTime = (t: string) => `${t.slice(0, 2)}~${t.slice(3)}`

/**
 * 조건 입력 영역. 입력 중인 조건(draft)은 '조건 적용'을 눌렀을 때만 반영한다.
 * 데모 단계에서는 선택 게시대의 상세 인구 조회 조건으로 쓰며, 추천 API는 아직 없다.
 */
function FilterPanel({ applied, onApply }: Props) {
  const [draft, setDraft] = useState<PopulationQuery>(applied)
  const set = <K extends keyof PopulationQuery>(key: K, value: PopulationQuery[K]) => setDraft({ ...draft, [key]: value })
  const toggle = (key: 'ageGroups' | 'timeBands', item: string, order: string[]) => {
    const list = draft[key].includes(item) ? draft[key].filter((v) => v !== item) : [...draft[key], item]
    set(key, order.filter((v) => list.includes(v)))
  }
  const ages = DEFAULT_QUERY.ageGroups
  const invalid = draft.ageGroups.length === 0 || draft.timeBands.length === 0

  return (
    <section className="panel filter-panel">
      <div className="panel-head">
        <h2>조건 설정</h2>
        <button type="button" className="link" onClick={() => setDraft(DEFAULT_QUERY)}>초기화</button>
      </div>

      <div className="panel-scroll">
        <fieldset>
          <legend>성별</legend>
          <div className="seg">
            {GENDER_OPTIONS.map(([value, label]) => (
              <label key={value}><input type="radio" name="gender" checked={draft.gender === value} onChange={() => set('gender', value)} />
                <span>{label}</span></label>
            ))}
          </div>
        </fieldset>

        <fieldset>
          <legend>연령대 <small>복수 선택</small></legend>
          <div className="chips cols3">
            {ages.map((a) => (
              <label key={a}><input type="checkbox" checked={draft.ageGroups.includes(a)} onChange={() => toggle('ageGroups', a, ages)} />
                <span>{AGE_LABEL[a]}</span></label>
            ))}
          </div>
        </fieldset>

        <fieldset>
          <legend>유동 유형</legend>
          <div className="seg">
            {FLOW_OPTIONS.map(([value, label]) => (
              <label key={value}><input type="radio" name="flowType" checked={draft.flowType === value} onChange={() => set('flowType', value)} />
                <span>{label}</span></label>
            ))}
          </div>
        </fieldset>

        <fieldset>
          <legend>시간대 <small>복수 선택</small></legend>
          <div className="chips cols4">
            {TIME_BANDS.map((t) => (
              <label key={t}><input type="checkbox" checked={draft.timeBands.includes(t)} onChange={() => toggle('timeBands', t, TIME_BANDS)} />
                <span>{shortTime(t)}</span></label>
            ))}
          </div>
        </fieldset>

        <fieldset>
          <legend>기간 <small>2025년</small></legend>
          <div className="seg">
            {PERIOD_OPTIONS.map(([value, label]) => (
              <label key={value}><input type="radio" name="periodKind" checked={draft.periodKind === value} onChange={() => set('periodKind', value)} />
                <span>{label}</span></label>
            ))}
          </div>
          {draft.periodKind === 'SEASON' && (
            <div className="chips cols4 sub-options">
              {SEASON_OPTIONS.map(([value, label, months]) => (
                <label key={value} title={months}><input type="radio" name="season" checked={draft.season === value} onChange={() => set('season', value)} />
                  <span>{label}</span></label>
              ))}
            </div>
          )}
          {draft.periodKind === 'MONTH' && (
            <div className="chips cols6 sub-options">
              {Array.from({ length: 12 }, (_, i) => i + 1).map((m) => (
                <label key={m}><input type="radio" name="month" checked={draft.month === m} onChange={() => set('month', m)} />
                  <span>{m}월</span></label>
              ))}
            </div>
          )}
        </fieldset>

        <fieldset>
          <legend>요일</legend>
          <div className="seg">
            {DAY_OPTIONS.map(([value, label]) => (
              <label key={value}><input type="radio" name="dayType" checked={draft.dayType === value} onChange={() => set('dayType', value)} />
                <span>{label}</span></label>
            ))}
          </div>
        </fieldset>
      </div>

      <div className="panel-actions">
        {invalid && <p className="hint">연령대와 시간대를 하나 이상 선택하세요.</p>}
        <button type="button" className="btn primary" disabled={invalid} onClick={() => onApply(draft)}>조건 적용</button>
        <button type="button" className="btn" disabled title="추천 API는 다음 단계에서 구현합니다.">
          추천 분석하기 <span className="soon">준비중</span>
        </button>
      </div>
    </section>
  )
}

export default FilterPanel
