import { useCallback, useEffect, useLayoutEffect, useMemo, useState } from 'react'
import './App.css'
import { api } from './lib/api'
import FilterPanel from './components/FilterPanel'
import RecommendationList from './components/RecommendationList'
import RightPanel from './components/RightPanel'
import SideTabs, { type LeftTab } from './components/SideTabs'
import PaletteSelect from './components/PaletteSelect'
import ThemeToggle, { type Theme } from './components/ThemeToggle'
import { DEFAULT_QUERY, PALETTES, type Palette } from './lib/constants'
import { sampleRecommendations } from './lib/sampleRecommendations'
import MapView, { type MapProvider } from './map/MapView'
import type { Board, BoardPopulation, Bounds, CellItem, MapCell, Meta, PopulationQuery, RecommendationItem } from './lib/types'

// 라이트/다크: 저장된 선택이 있으면 그것을, 없으면 OS 설정을 따른다.
const THEME_KEY = 'flowsign-theme'
function initialTheme(): Theme {
  try {
    const saved = localStorage.getItem(THEME_KEY)
    if (saved === 'light' || saved === 'dark') return saved
  } catch {
    // 저장소를 쓸 수 없으면 OS 설정으로 시작한다.
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

// 색상 팔레트: 저장된 선택이 있으면 그것을, 없으면 A안(coral)으로 시작한다.
const PALETTE_KEY = 'flowsign-palette'
function initialPalette(): Palette {
  try {
    const saved = localStorage.getItem(PALETTE_KEY)
    const found = PALETTES.find((p) => p.key === saved)
    if (found) return found.key
  } catch {
    // 저장소를 쓸 수 없으면 기본 팔레트로 시작한다.
  }
  return 'coral'
}

// 데모 링크용 초기 상태: ?map=naver|leaflet&board=3&grid=1&cells=1&allcells=1
const params = new URLSearchParams(window.location.search)
const envProvider: MapProvider =
  import.meta.env.VITE_MAP_PROVIDER === 'naver' && import.meta.env.VITE_NAVER_MAP_CLIENT_ID ? 'naver' : 'leaflet'
const INITIAL_PROVIDER: MapProvider =
  params.get('map') === 'naver' || params.get('map') === 'leaflet' ? (params.get('map') as MapProvider) : envProvider

function App() {
  const [meta, setMeta] = useState<Meta | null>(null)
  const [boards, setBoards] = useState<Board[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)

  const [provider, setProvider] = useState<MapProvider>(INITIAL_PROVIDER)
  const [showGrid, setShowGrid] = useState(params.get('grid') === '1')
  const [showCells, setShowCells] = useState(params.get('cells') === '1')
  const [showAllCells, setShowAllCells] = useState(params.get('allcells') === '1')
  const [view, setView] = useState<Bounds | null>(null)
  const [allCells, setAllCells] = useState<MapCell[] | null>(null)
  const [allCellsNotice, setAllCellsNotice] = useState<string | null>(null)
  const [query, setQuery] = useState<PopulationQuery>(DEFAULT_QUERY)
  const [theme, setTheme] = useState<Theme>(initialTheme)

  useLayoutEffect(() => {
    document.documentElement.dataset.theme = theme
  }, [theme])
  const toggleTheme = useCallback(() => {
    const next: Theme = theme === 'dark' ? 'light' : 'dark'
    setTheme(next)
    try {
      localStorage.setItem(THEME_KEY, next)
    } catch {
      // 저장하지 못해도 이번 화면에서는 바뀐다.
    }
  }, [theme])

  const [palette, setPalette] = useState<Palette>(initialPalette)
  useLayoutEffect(() => {
    document.documentElement.dataset.palette = palette
  }, [palette])
  const changePalette = useCallback((next: Palette) => {
    setPalette(next)
    try {
      localStorage.setItem(PALETTE_KEY, next)
    } catch {
      // 저장하지 못해도 이번 화면에서는 바뀐다.
    }
  }, [])

  const [selectedId, setSelectedId] = useState<string | null>(params.get('board'))
  const [board, setBoard] = useState<Board | null>(null)
  const [population, setPopulation] = useState<BoardPopulation | null>(null)
  const [cells, setCells] = useState<CellItem[] | null>(null)
  // 상세 조회 한 번을 나타내는 값. 선택한 게시대나 조건이 바뀌면 새 객체가 된다.
  const request = useMemo(() => ({ selectedId, query }), [selectedId, query])
  // 응답(성공·실패)이 끝난 요청. 지금 요청과 다르면 아직 불러오는 중이다.
  const [settled, setSettled] = useState<object | null>(null)
  const [failure, setFailure] = useState<{ request: object; message: string } | null>(null)
  const detailLoading = selectedId !== null && settled !== request
  const detailError = failure?.request === request ? failure.message : null

  // 첫 진입: /meta + /boards (전역 인구 API는 호출하지 않는다)
  useEffect(() => {
    const ctrl = new AbortController()
    Promise.all([api.meta(ctrl.signal), api.boards(ctrl.signal)])
      .then(([m, b]) => {
        setMeta(m)
        setBoards(b)
      })
      .catch((e: Error) => {
        if (!ctrl.signal.aborted) setLoadError(e.message)
      })
    return () => ctrl.abort()
  }, [])

  // 게시대 선택 또는 조건 변경: 상세 + 인구 병렬 조회
  useEffect(() => {
    const { selectedId, query } = request
    if (!selectedId) return
    const ctrl = new AbortController()
    Promise.all([api.board(selectedId, ctrl.signal), api.population(selectedId, query, ctrl.signal)])
      .then(([b, p]) => {
        setBoard(b)
        setPopulation(p)
      })
      .catch((e: Error) => {
        if (!ctrl.signal.aborted) setFailure({ request, message: e.message })
      })
      .finally(() => {
        if (!ctrl.signal.aborted) setSettled(request)
      })
    return () => ctrl.abort()
  }, [request])

  // 3×3 Cell 보기를 켰을 때만 /cells 호출
  useEffect(() => {
    const { selectedId, query } = request
    if (!selectedId || !showCells) return
    const ctrl = new AbortController()
    api.cells(selectedId, query, ctrl.signal)
      .then(setCells)
      .catch((e: Error) => {
        if (!ctrl.signal.aborted) setFailure({ request, message: e.message })
      })
    return () => ctrl.abort()
  }, [request, showCells])

  // 통신사 Cell 레이어: 켜져 있으면 지도 이동이 끝날 때마다 화면 범위의 Cell만 다시 조회
  useEffect(() => {
    if (!showAllCells || !view) return
    const ctrl = new AbortController()
    api.cellsInView(view, ctrl.signal)
      .then((r) => {
        setAllCells(r.cells)
        setAllCellsNotice(r.truncated ? `화면 범위의 Cell이 ${r.limit.toLocaleString()}개를 넘습니다. 지도를 확대하면 표시됩니다.` : null)
      })
      .catch((e: Error) => {
        if (!ctrl.signal.aborted) setAllCellsNotice(`통신사 Cell을 불러오지 못했습니다: ${e.message}`)
      })
    return () => ctrl.abort()
  }, [showAllCells, view])

  // 오른쪽 상세 패널을 접었는지. 게시대를 새로 고르면 다시 펼친다.
  const [rightOpen, setRightOpen] = useState(true)
  const selectBoard = useCallback((id: string) => {
    setSelectedId(id)
    setRightOpen(true)
  }, [])

  // 왼쪽 탭에서 고른 패널. 열려 있는 탭을 다시 누르면 null이 되어 패널이 닫힌다.
  const [leftTab, setLeftTab] = useState<LeftTab | null>('filter')
  const toggleLeftTab = useCallback((tab: LeftTab) => setLeftTab((current) => (current === tab ? null : tab)), [])
  // 추천 결과(시안: 예시 값). 아직 분석하지 않았으면 null
  const [recommendations, setRecommendations] = useState<RecommendationItem[] | null>(null)
  const [focus, setFocus] = useState<{ latitude: number; longitude: number } | null>(null)
  const recommend = useCallback((q: PopulationQuery) => {
    setQuery(q)
    setRecommendations(sampleRecommendations(boards))
    setLeftTab('result')
  }, [boards])
  // 추천 카드를 고르면 게시대를 선택하고 지도를 그 위치로 옮긴다.
  const selectRecommended = useCallback((id: string) => {
    setSelectedId(id)
    setRightOpen(true)
    const target = boards.find((b) => b.boardId === id)
    if (target) setFocus({ latitude: target.latitude, longitude: target.longitude })
  }, [boards])

  // 레이어를 끄면 그려 둔 Cell도 함께 비운다 (다시 켰을 때 이전 결과가 잠깐 보이지 않도록)
  const toggleCells = useCallback((on: boolean) => {
    setShowCells(on)
    if (!on) setCells(null)
  }, [])
  const toggleAllCells = useCallback((on: boolean) => {
    setShowAllCells(on)
    if (!on) {
      setAllCells(null)
      setAllCellsNotice(null)
    }
  }, [])

  return (
    <div className="app">
      <header className="app-header">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="20" height="20">
              <path d="M6 20V6h12M6 13h9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" fill="none" />
            </svg>
          </span>
          <h1>FLOWSIGN</h1>
        </div>
        <p>유동인구 데이터 기반 옥외광고 게시대 추천 서비스 · 데모 초안</p>
        <div className="header-tools">
          <PaletteSelect palette={palette} onChange={changePalette} />
          <ThemeToggle theme={theme} onToggle={toggleTheme} />
        </div>
      </header>

      {loadError && <div className="banner error">데이터를 불러오지 못했습니다: {loadError}</div>}

      <main className={`app-body${leftTab ? '' : ' left-closed'}${rightOpen ? '' : ' right-closed'}`}>
        <SideTabs tab={leftTab} onChange={toggleLeftTab} />
        <FilterPanel applied={query} onApply={setQuery} onRecommend={recommend} hidden={leftTab !== 'filter'} />
        <RecommendationList items={recommendations} query={query} selectedBoardId={selectedId} onSelect={selectRecommended}
          onEditConditions={() => setLeftTab('filter')} hidden={leftTab !== 'result'} />
        <MapView
          provider={provider}
          onProviderChange={setProvider}
          boards={boards}
          selectedBoardId={selectedId}
          onSelectBoard={selectBoard}
          grid={meta?.grid ?? null}
          showGrid={showGrid}
          onShowGridChange={setShowGrid}
          showCells={showCells}
          onShowCellsChange={toggleCells}
          cells={cells}
          showAllCells={showAllCells}
          onShowAllCellsChange={toggleAllCells}
          allCells={allCells}
          allCellsNotice={allCellsNotice}
          focus={focus}
          palette={palette}
          onViewChange={setView}
        />
        <RightPanel
          board={board}
          population={population}
          loading={detailLoading}
          error={detailError}
          open={rightOpen}
          onToggle={() => setRightOpen((open) => !open)}
          recommendation={recommendations?.find((r) => r.boardId === board?.boardId) ?? null}
        />
      </main>

      <footer className="app-footer">
        {meta?.notice ?? '통신사 유동인구를 활용한 노출 가능성 추정이며 실제 광고 성과를 의미하지 않습니다.'}
      </footer>
    </div>
  )
}

export default App
