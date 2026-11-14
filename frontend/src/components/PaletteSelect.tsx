import { PALETTES, type Palette } from '../lib/constants'
import './PaletteSelect.css'

interface Props {
  palette: Palette
  onChange: (p: Palette) => void
}

/** 색상 팔레트 선택. 선택값은 App이 저장한다. */
function PaletteSelect({ palette, onChange }: Props) {
  return (
    <div className="palette-select" role="radiogroup" aria-label="색상 팔레트">
      {PALETTES.map((p) => (
        <button
          key={p.key}
          type="button"
          role="radio"
          aria-checked={palette === p.key}
          aria-label={p.label}
          title={p.label}
          className={palette === p.key ? 'active' : ''}
          style={{ background: p.color }}
          onClick={() => onChange(p.key)}
        />
      ))}
    </div>
  )
}

export default PaletteSelect
