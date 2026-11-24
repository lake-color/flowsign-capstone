import { useEffect, useRef, useState, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { ApiError, api } from '../lib/api'
import type { Board, InquiryRequest, InquiryResult, StoreCategory } from '../lib/types'
import { PURPOSE_LABEL, STORE_CATEGORY_LABEL } from '../lib/constants'
import './InquiryModal.css'

interface Props {
  /** 문의할 게시대 (canInquire=true인 상업용·겸용만 연다) */
  board: Board
  onClose: () => void
}

/** 명세 4.7 서버 검사와 같은 기준 */
const MAX_MESSAGE = 300
const MAX_BUSINESS_NAME = 100
const MIN_YEAR = 2026
const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/
const PHONE = /^0[0-9]{1,2}-?[0-9]{3,4}-?[0-9]{4}$/

// 업종 선택지: 주변 업종 묶음 15개 코드(명세 4.8) + 기타(OTHER)
type Industry = StoreCategory | 'OTHER'
const INDUSTRIES: [Industry, string][] = [...(Object.entries(STORE_CATEGORY_LABEL) as [StoreCategory, string][]), ['OTHER', '기타']]

// 동의 문구는 명세에서 미확정이다. 확정되면 이 문구와 버전 값을 바꾼다.
const CONSENT_VERSION = 'demo-v1'
const CONSENT_TEXT = '문의 처리를 위해 업종, 상호, 연락처, 문의 내용을 수집합니다.'
const CONSENT_NOTE = '(시연용 임시 문구 — 수집 목적·보관 기간은 확정 후 안내)'

const HALF_LABEL = { FIRST: '상반월(1~15일)', SECOND: '하반월(16일~말일)' } as const
/** 연도 선택지: 기본 연도부터 3년 뒤까지 */
const YEAR_SPAN = 3

/** 문자 수(이모지 등도 1자). 서버도 code point 기준으로 센다. */
const charCount = (s: string) => [...s].length

/** UUID v4. randomUUID는 https·localhost에서만 있어 LAN 주소로 열었을 때를 위해 직접 만든다. */
function uuidV4(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const b = crypto.getRandomValues(new Uint8Array(16))
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}

interface Form {
  year: number
  month: number
  half: 'FIRST' | 'SECOND'
  industry: Industry | ''
  businessName: string
  contact: string
  productionAssistanceRequired: boolean
  message: string
  privacyConsent: boolean
}

function initialForm(): Form {
  // 기본 희망 시기: 다음 달 상반월
  const now = new Date()
  const next = new Date(now.getFullYear(), now.getMonth() + 1, 1)
  const year = Math.max(MIN_YEAR, next.getFullYear())
  return {
    year, month: year > next.getFullYear() ? 1 : next.getMonth() + 1, half: 'FIRST',
    industry: '', businessName: '', contact: '', productionAssistanceRequired: false, message: '', privacyConsent: false,
  }
}

type Errors = Partial<Record<'industry' | 'contact' | 'message' | 'privacyConsent', string>>

function validate(f: Form): Errors {
  const e: Errors = {}
  if (!f.industry) e.industry = '업종을 선택하세요.'
  const contact = f.contact.trim()
  if (!contact) e.contact = '연락처를 입력하세요.'
  else if (!EMAIL.test(contact) && !PHONE.test(contact)) e.contact = '전화번호(예: 010-1234-5678) 또는 이메일 형식으로 입력하세요.'
  if (charCount(f.message.trim()) > MAX_MESSAGE) e.message = `문의 내용은 ${MAX_MESSAGE}자 이하로 입력하세요.`
  if (!f.privacyConsent) e.privacyConsent = '개인정보 수집·이용에 동의해야 문의할 수 있습니다.'
  return e
}

function toRequest(boardId: string, f: Form): InquiryRequest {
  const businessName = f.businessName.trim()
  const message = f.message.trim()
  return {
    boardId,
    desiredPosting: { year: f.year, month: f.month, half: f.half },
    industry: f.industry,
    ...(businessName ? { businessName } : {}),
    contact: f.contact.trim(),
    productionAssistanceRequired: f.productionAssistanceRequired,
    ...(message ? { message } : {}),
    privacyConsent: true,
    privacyConsentVersion: CONSENT_VERSION,
  }
}

/** 실패 응답을 화면 문구로. 연락처는 서버도 오류 메시지에 넣지 않는다. */
function errorText(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 'INQUIRY_NOT_ALLOWED') return '행정용 게시대는 광고 문의를 받지 않습니다.'
    if (err.code === 'BOARD_NOT_FOUND') return '게시대 정보를 찾을 수 없습니다. 지도에서 다시 선택하세요.'
    if (err.code === 'CONSENT_REQUIRED') return '개인정보 수집·이용에 동의해야 문의할 수 있습니다.'
    if (err.code === 'IDEMPOTENCY_CONFLICT') return '이전에 보낸 문의와 내용이 달라 접수하지 못했습니다. 내용을 확인하고 다시 제출하세요.'
    if (err.status === 400) return err.message
    if (err.status < 500) return `문의를 접수하지 못했습니다: ${err.message}`
  }
  // 타임아웃·네트워크 오류·5xx는 서버에 저장됐을 수도 있다. 같은 키로 다시 보내면 중복 저장되지 않는다.
  return '서버 응답을 받지 못했습니다. 다시 제출하면 같은 문의로 처리되어 중복 접수되지 않습니다.'
}

/**
 * 광고 신청 문의 모달 (명세 4.7). 예약·결제가 아니라 문의 접수만 한다.
 * Idempotency-Key는 제출을 시작할 때 만들고, 실패 후 다시 제출해도 내용이 같으면 같은 키를 쓴다.
 * 내용을 고치면 새 문의이므로 키를 버린다(같은 키·다른 내용은 서버가 409로 막는다).
 */
function InquiryModal({ board, onClose }: Props) {
  const [form, setForm] = useState<Form>(initialForm)
  const [years] = useState(() => Array.from({ length: YEAR_SPAN + 1 }, (_, i) => form.year + i))
  const [showErrors, setShowErrors] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  // 서버가 400 CONSENT_REQUIRED를 주면 동의 체크박스를 강조한다 (명세 5장)
  const [consentRequired, setConsentRequired] = useState(false)
  const [result, setResult] = useState<InquiryResult | null>(null)
  const keyRef = useRef<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)

  const errors = validate(form)
  const messageLength = charCount(form.message.trim())

  // 모달이 닫히면 진행 중인 요청을 끊는다(응답이 와도 화면에 반영하지 않음).
  useEffect(() => () => abortRef.current?.abort(), [])

  // Esc로 닫기 (보내는 중에는 결과를 확인해야 하므로 닫지 않는다)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !submitting) onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [submitting, onClose])

  function update<K extends keyof Form>(key: K, value: Form[K]) {
    setForm((f) => ({ ...f, [key]: value }))
    keyRef.current = null
    setSubmitError(null)
    if (key === 'privacyConsent') setConsentRequired(false)
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (submitting) return
    setShowErrors(true)
    if (Object.keys(errors).length > 0) return

    keyRef.current ??= uuidV4()
    const controller = new AbortController()
    abortRef.current = controller
    setSubmitting(true)
    setSubmitError(null)
    try {
      setResult(await api.inquire(toRequest(board.boardId, form), keyRef.current, controller.signal))
    } catch (err) {
      if (controller.signal.aborted) return
      if (err instanceof ApiError && err.code === 'IDEMPOTENCY_CONFLICT') keyRef.current = null
      if (err instanceof ApiError && err.code === 'CONSENT_REQUIRED') setConsentRequired(true)
      setSubmitError(errorText(err))
    } finally {
      if (!controller.signal.aborted) setSubmitting(false)
    }
  }

  return createPortal(
    <div className="inquiry-backdrop">
      <div className="inquiry-modal" role="dialog" aria-modal="true" aria-labelledby="inquiry-title">
        <button type="button" className="inquiry-close" onClick={onClose} disabled={submitting} aria-label="닫기" title="닫기">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" fill="none" />
          </svg>
        </button>
        <h2 id="inquiry-title">광고 신청 문의</h2>
        <p className="inquiry-board">
          <span className={`badge ${board.purpose}`}>{PURPOSE_LABEL[board.purpose]}</span>
          <b>{board.name}</b>
        </p>

        {result ? (
          <div className="inquiry-done" role="status">
            <h3>문의가 접수되었습니다</h3>
            <dl className="info">
              <dt>접수 번호</dt><dd>{result.inquiryId}</dd>
              <dt>희망 시기</dt><dd>{result.desiredPosting.year}년 {result.desiredPosting.month}월 {HALF_LABEL[result.desiredPosting.half]}</dd>
              <dt>접수 시각</dt><dd>{new Date(result.receivedAt).toLocaleString('ko-KR')}</dd>
            </dl>
            <p className="inquiry-note">{result.notice}</p>
            <p className="inquiry-note">예약이나 결제가 확정된 것이 아니라 문의만 접수된 상태입니다.</p>
            <button type="button" className="btn primary" onClick={onClose} autoFocus>닫기</button>
          </div>
        ) : (
          <form className="inquiry-form" onSubmit={submit} noValidate>
            <p className="inquiry-note">게시대 예약·결제가 아닌 <b>광고 신청 문의</b>입니다.<br />게시는 지자체 절차에 따라 정해집니다.</p>

            <fieldset>
              <legend>희망 게시 시기 <em>*</em></legend>
              <div className="inquiry-row">
                <select value={form.year} onChange={(e) => update('year', Number(e.target.value))} aria-label="연도">
                  {years.map((y) => <option key={y} value={y}>{y}년</option>)}
                </select>
                <select value={form.month} onChange={(e) => update('month', Number(e.target.value))} aria-label="월">
                  {Array.from({ length: 12 }, (_, i) => i + 1).map((m) => <option key={m} value={m}>{m}월</option>)}
                </select>
                <select value={form.half} onChange={(e) => update('half', e.target.value as Form['half'])} aria-label="상·하반월">
                  <option value="FIRST">{HALF_LABEL.FIRST}</option>
                  <option value="SECOND">{HALF_LABEL.SECOND}</option>
                </select>
              </div>
            </fieldset>

            <label>
              <span>업종 <em>*</em></span>
              <select value={form.industry} autoFocus onChange={(e) => update('industry', e.target.value as Form['industry'])}
                aria-invalid={showErrors && !!errors.industry}>
                <option value="" disabled>업종을 선택하세요</option>
                {INDUSTRIES.map(([code, label]) => <option key={code} value={code}>{label}</option>)}
              </select>
              {showErrors && errors.industry && <small className="error">{errors.industry}</small>}
            </label>

            <label>
              <span>상호 <small>(선택)</small></span>
              <input type="text" value={form.businessName} maxLength={MAX_BUSINESS_NAME}
                onChange={(e) => update('businessName', e.target.value)} />
            </label>

            <label>
              <span>연락처 <em>*</em></span>
              <input type="text" value={form.contact} maxLength={100} placeholder="전화번호 또는 이메일" autoComplete="off"
                onChange={(e) => update('contact', e.target.value)} aria-invalid={showErrors && !!errors.contact} />
              {showErrors && errors.contact && <small className="error">{errors.contact}</small>}
            </label>

            <label className="inquiry-check">
              <input type="checkbox" checked={form.productionAssistanceRequired}
                onChange={(e) => update('productionAssistanceRequired', e.target.checked)} />
              <span>현수막 제작 도움이 필요합니다</span>
            </label>

            <label>
              <span>문의 내용 <small>(선택)</small></span>
              <textarea value={form.message} rows={4} onChange={(e) => update('message', e.target.value)}
                aria-invalid={showErrors && !!errors.message} />
              <small className={`inquiry-count${messageLength > MAX_MESSAGE ? ' over' : ''}`}>{messageLength}/{MAX_MESSAGE}</small>
              {showErrors && errors.message && <small className="error">{errors.message}</small>}
            </label>

            <div className={`inquiry-consent${consentRequired || (showErrors && errors.privacyConsent) ? ' invalid' : ''}`}>
              <p>{CONSENT_TEXT}<br />{CONSENT_NOTE}</p>
              <label className="inquiry-check">
                <input type="checkbox" checked={form.privacyConsent} onChange={(e) => update('privacyConsent', e.target.checked)}
                  aria-invalid={consentRequired || (showErrors && !!errors.privacyConsent)} />
                <span>개인정보 수집·이용에 동의합니다 <em>*</em></span>
              </label>
              {showErrors && errors.privacyConsent && <small className="error">{errors.privacyConsent}</small>}
            </div>

            {submitError && <p className="error" role="alert">{submitError}</p>}

            <div className="inquiry-actions">
              <button type="button" className="btn" onClick={onClose} disabled={submitting}>취소</button>
              <button type="submit" className="btn primary" disabled={submitting}>
                {submitting ? '보내는 중…' : submitError ? '다시 제출' : '문의 보내기'}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>,
    document.body,
  )
}

export default InquiryModal
