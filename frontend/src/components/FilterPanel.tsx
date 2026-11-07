import { useState } from 'react'
import { AGE_LABEL, DEFAULT_QUERY, TIME_BANDS, timeLabel } from '../constants'
import type { PopulationQuery } from '../types'

interface Props {
  applied: PopulationQuery
  onApply: (q: PopulationQuery) => void
}

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
      <h2>조건</h2>

      <fieldset>
        <legend>성별</legend>
        {(['ALL', 'MALE', 'FEMALE'] as const).map((g) => (
          <label key={g}><input type="radio" checked={draft.gender === g} onChange={() => set('gender', g)} />
            {{ ALL: '전체', MALE: '남성', FEMALE: '여성' }[g]}</label>
        ))}
      </fieldset>

      <fieldset>
        <legend>연령대</legend>
        {ages.map((a) => (
          <label key={a}><input type="checkbox" checked={draft.ageGroups.includes(a)} onChange={() => toggle('ageGroups', a, ages)} />
            {AGE_LABEL[a]}</label>
        ))}
      </fieldset>

      <fieldset>
        <legend>유동 유형</legend>
        <select value={draft.flowType} onChange={(e) => set('flowType', e.target.value as PopulationQuery['flowType'])}>
          <option value="ALL">전체(체류+보행+주행)</option>
          <option value="STAY">체류</option>
          <option value="WALK">보행</option>
          <option value="DRIVE">주행</option>
        </select>
      </fieldset>

      <fieldset>
        <legend>시간대</legend>
        {TIME_BANDS.map((t) => (
          <label key={t}><input type="checkbox" checked={draft.timeBands.includes(t)} onChange={() => toggle('timeBands', t, TIME_BANDS)} />
            {timeLabel(t)}</label>
        ))}
      </fieldset>

      <fieldset>
        <legend>기간 (2025년)</legend>
        <select value={draft.periodKind} onChange={(e) => set('periodKind', e.target.value as PopulationQuery['periodKind'])}>
          <option value="YEAR">연간</option>
          <option value="SEASON">계절</option>
          <option value="MONTH">월</option>
        </select>
        {draft.periodKind === 'SEASON' && (
          <select value={draft.season} onChange={(e) => set('season', e.target.value as PopulationQuery['season'])}>
            <option value="SPRING">봄 (3~5월)</option>
            <option value="SUMMER">여름 (6~8월)</option>
            <option value="AUTUMN">가을 (9~11월)</option>
            <option value="WINTER">겨울 (1·2·12월)</option>
          </select>
        )}
        {draft.periodKind === 'MONTH' && (
          <select value={draft.month} onChange={(e) => set('month', Number(e.target.value))}>
            {Array.from({ length: 12 }, (_, i) => <option key={i} value={i + 1}>{i + 1}월</option>)}
          </select>
        )}
      </fieldset>

      <fieldset>
        <legend>주중/주말</legend>
        <select value={draft.dayType} onChange={(e) => set('dayType', e.target.value as PopulationQuery['dayType'])}>
          <option value="ALL">전체</option>
          <option value="WEEKDAY">주중</option>
          <option value="WEEKEND">주말</option>
        </select>
      </fieldset>

      <button className="primary" disabled={invalid} onClick={() => onApply(draft)}>조건 적용</button>
      <button disabled title="추천 API는 다음 단계에서 구현합니다.">추천 분석하기 (준비 중)</button>
      {invalid && <p className="hint">연령대와 시간대를 하나 이상 선택하세요.</p>}
    </section>
  )
}

export default FilterPanel
