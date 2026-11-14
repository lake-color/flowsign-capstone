import './ThemeToggle.css'

export type Theme = 'light' | 'dark'

interface Props {
  theme: Theme
  onToggle: () => void
}

/** 라이트/다크 전환 스위치. 선택한 모드는 App이 저장한다. */
function ThemeToggle({ theme, onToggle }: Props) {
  const dark = theme === 'dark'
  return (
    <button
      type="button"
      role="switch"
      aria-checked={dark}
      aria-label="다크 모드"
      title={dark ? '라이트 모드로 전환' : '다크 모드로 전환'}
      className={`theme-toggle${dark ? ' dark' : ''}`}
      onClick={onToggle}
    >
      <span className="knob" />
      <svg className={dark ? '' : 'on'} viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="12" cy="12" r="4" fill="currentColor" />
        {[0, 45, 90, 135, 180, 225, 270, 315].map((a) => (
          <line key={a} x1="12" y1="2.5" x2="12" y2="5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" transform={`rotate(${a} 12 12)`} />
        ))}
      </svg>
      <svg className={dark ? 'on' : ''} viewBox="0 0 24 24" aria-hidden="true">
        <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z" fill="currentColor" />
      </svg>
    </button>
  )
}

export default ThemeToggle
