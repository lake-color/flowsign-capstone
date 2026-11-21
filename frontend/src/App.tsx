import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import './App.css'
import { api, ApiError } from './lib/api'
import FilterPanel from './components/FilterPanel'
import LayerMenu from './components/LayerMenu'
import RecommendationList from './components/RecommendationList'
import RightPanel from './components/RightPanel'
import SideTabs, { type LeftTab } from './components/SideTabs'
import PaletteSelect from './components/PaletteSelect'
import ThemeToggle, { type Theme } from './components/ThemeToggle'
import { DEFAULT_QUERY, PALETTES, type Palette } from './lib/constants'
import MapView, { type MapProvider } from './map/MapView'
import type {
  Board, BoardPopulation, BoardStores, Bounds, CellItem, MapCell, Meta, PopulationError, PopulationQuery, Purpose,
  RecommendationResult, StoreCategory,
} from './lib/types'

const toPopulationError = (e: Error): PopulationError => ({ code: e instanceof ApiError ? e.code : null, message: e.message })

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

// 데모 링크용 초기 상태: ?map=naver|leaflet&board=3&grid=1&cells=1&allcells=1&stores=1
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
  const [showStores, setShowStores] = useState(params.get('stores') === '1')
  // 상세 패널에서 고른 업종 (지도에서 강조). 같은 업종을 다시 누르면 해제한다.
  const [highlightCategory, setHighlightCategory] = useState<StoreCategory | null>(null)
  // 용도별 게시대 마커 표시 (가시성 메뉴). 지도 마커만 거르고 추천 결과 목록에는 영향이 없다.
  const [purposeVisible, setPurposeVisible] = useState<Record<Purpose, boolean>>({ COMMERCIAL: true, MIXED: true, ADMINISTRATIVE: true })
  const changePurposeVisible = useCallback((purpose: Purpose, visible: boolean) => {
    setPurposeVisible((current) => ({ ...current, [purpose]: visible }))
  }, [])
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
  const [cells, setCells] = useState<CellItem[] | null>(null)
  // 인구 조회 한 번을 나타내는 값. 선택한 게시대나 조건이 바뀌면 새 객체가 된다.
  const request = useMemo(() => ({ selectedId, query }), [selectedId, query])

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

  // 게시대 상세와 인구는 따로 조회한다. 인구가 503 DATA_NOT_READY·400 UNSUPPORTED_FILTER여도
  // 게시대 기본 정보와 주변 업종은 보여야 하기 때문이다 (명세 5장).

  // 게시대 선택: 상세 조회 (인구 조건과 무관하므로 게시대가 바뀔 때만)
  const [boardResult, setBoardResult] = useState<{ boardId: string; data: Board | null; error: string | null } | null>(null)
  useEffect(() => {
    if (!selectedId) return
    const ctrl = new AbortController()
    api.board(selectedId, ctrl.signal)
      .then((data) => setBoardResult({ boardId: selectedId, data, error: null }))
      .catch((e: Error) => {
        if (!ctrl.signal.aborted) setBoardResult({ boardId: selectedId, data: null, error: e.message })
      })
    return () => ctrl.abort()
  }, [selectedId])
  const currentBoard = boardResult?.boardId === selectedId ? boardResult : null
  const boardLoading = selectedId !== null && !currentBoard

  // 게시대 선택 또는 조건 변경: 인구 조회. 결과는 그 요청일 때만 쓴다 (실패 시 이전 조건의 값을 남기지 않는다).
  const [populationResult, setPopulationResult] = useState<
    { request: object; data: BoardPopulation | null; error: PopulationError | null } | null>(null)
  useEffect(() => {
    const { selectedId, query } = request
    if (!selectedId) return
    const ctrl = new AbortController()
    api.population(selectedId, query, ctrl.signal)
      .then((data) => setPopulationResult({ request, data, error: null }))
      .catch((e: Error) => {
        if (!ctrl.signal.aborted) setPopulationResult({ request, data: null, error: toPopulationError(e) })
      })
    return () => ctrl.abort()
  }, [request])
  const currentPopulation = populationResult?.request === request ? populationResult : null
  const populationLoading = selectedId !== null && !currentPopulation

  // 게시대 선택: 주변 상가 조회 (인구 조건과 무관하므로 게시대가 바뀔 때만). 패널 개수와 지도 점이 같은 응답을 쓴다.
  const [stores, setStores] = useState<{ boardId: string; data: BoardStores } | null>(null)
  const [storesFailure, setStoresFailure] = useState<{ boardId: string; message: string } | null>(null)
  useEffect(() => {
    if (!selectedId) return
    const ctrl = new AbortController()
    api.stores(selectedId, ctrl.signal)
      .then((data) => setStores({ boardId: selectedId, data }))
      .catch((e: Error) => {
        if (!ctrl.signal.aborted) setStoresFailure({ boardId: selectedId, message: e.message })
      })
    return () => ctrl.abort()
  }, [selectedId])
  const currentStores = stores?.boardId === selectedId ? stores.data : null
  const storesError = storesFailure?.boardId === selectedId && !currentStores ? storesFailure.message : null

  // 업종을 고르면 강조하고, 레이어가 꺼져 있으면 켠다.
  const selectCategory = useCallback((category: StoreCategory) => {
    setHighlightCategory((current) => (current === category ? null : category))
    setShowStores(true)
  }, [])

  // 3×3 Cell 보기를 켰을 때만 /cells 호출
  const [cellsFailure, setCellsFailure] = useState<{ request: object; message: string } | null>(null)
  useEffect(() => {
    const { selectedId, query } = request
    if (!selectedId || !showCells) return
    const ctrl = new AbortController()
    api.cells(selectedId, query, ctrl.signal)
      .then(setCells)
      .catch((e: Error) => {
        if (ctrl.signal.aborted) return
        // 이전 게시대·조건의 Cell이 지도에 남지 않도록 비운다.
        setCells(null)
        setCellsFailure({ request, message: e.message })
      })
    return () => ctrl.abort()
  }, [request, showCells])
  const cellsError = showCells && cellsFailure?.request === request ? cellsFailure.message : null

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
    setHighlightCategory(null)
  }, [])

  // 왼쪽 탭에서 고른 패널. 열려 있는 탭을 다시 누르면 null이 되어 패널이 닫힌다.
  const [leftTab, setLeftTab] = useState<LeftTab | null>('filter')
  const toggleLeftTab = useCallback((tab: LeftTab) => setLeftTab((current) => (current === tab ? null : tab)), [])
  // 추천 결과(POST /recommendations/search). 아직 분석하지 않았으면 null. '더 보기'로 받은 페이지는 items에 이어 붙인다.
  const [recommendation, setRecommendation] = useState<RecommendationResult | null>(null)
  // 지금 보이는 추천 결과를 만든 조건. '더 보기'도 같은 조건으로 요청한다.
  const [recommendQuery, setRecommendQuery] = useState<PopulationQuery | null>(null)
  // 새로 분석 중('analyze')인지, 다음 페이지를 받는 중('more')인지
  const [recommendLoading, setRecommendLoading] = useState<'analyze' | 'more' | null>(null)
  const [recommendError, setRecommendError] = useState<string | null>(null)
  const recommendRequest = useRef<AbortController | null>(null)
  const [focus, setFocus] = useState<{ latitude: number; longitude: number } | null>(null)

  /**
   * page 0이면 새로 분석하고, 그 뒤 페이지는 기존 결과에 이어 붙인다. 진행 중인 이전 요청은 취소한다.
   * 조건과 결과는 응답이 성공했을 때만 바꾼다(명세 6장). 실패하면 이전 결과와 조건을 그대로 두고 오류만 보여준다.
   */
  const fetchRecommendations = useCallback((q: PopulationQuery, page: number) => {
    recommendRequest.current?.abort()
    const ctrl = new AbortController()
    recommendRequest.current = ctrl
    setRecommendLoading(page === 0 ? 'analyze' : 'more')
    setRecommendError(null)
    api.recommend(q, page, ctrl.signal)
      .then((r) => {
        if (page === 0) {
          setQuery(q)
          setRecommendQuery(q)
        }
        setRecommendation((prev) => (page > 0 && prev ? { ...r, items: [...prev.items, ...r.items] } : r))
      })
      .catch((e: Error) => {
        if (!ctrl.signal.aborted) setRecommendError(e.message)
      })
      .finally(() => {
        if (!ctrl.signal.aborted) setRecommendLoading(null)
      })
  }, [])

  // '추천 분석하기' 클릭 때만 호출한다 (명세 4.6: 조건 편집 중 자동 호출 금지)
  const recommend = useCallback((q: PopulationQuery) => {
    setLeftTab('result')
    fetchRecommendations(q, 0)
  }, [fetchRecommendations])

  const recommendMore = useCallback(() => {
    if (recommendation && recommendQuery) fetchRecommendations(recommendQuery, recommendation.page + 1)
  }, [fetchRecommendations, recommendation, recommendQuery])
  // 추천 카드를 고르면 게시대를 선택하고 지도를 그 위치로 옮긴다.
  const selectRecommended = useCallback((id: string) => {
    setSelectedId(id)
    setRightOpen(true)
    setHighlightCategory(null)
    const target = boards.find((b) => b.boardId === id)
    if (target) setFocus({ latitude: target.latitude, longitude: target.longitude })
  }, [boards])

  // 지도에 그릴 게시대. 숨긴 용도는 거르되, 선택한 게시대는 용도를 숨겨도 남겨 둔다.
  const visibleBoards = useMemo(
    () => boards.filter((b) => purposeVisible[b.purpose] || b.boardId === selectedId),
    [boards, purposeVisible, selectedId],
  )

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
        <SideTabs tab={leftTab} onChange={toggleLeftTab}>
          <LayerMenu
            showGrid={showGrid}
            onShowGridChange={setShowGrid}
            gridReady={meta?.grid?.status === 'VERIFIED'}
            showAllCells={showAllCells}
            onShowAllCellsChange={toggleAllCells}
            showCells={showCells}
            onShowCellsChange={toggleCells}
            hasSelectedBoard={selectedId !== null}
            showStores={showStores}
            onShowStoresChange={setShowStores}
            purposeVisible={purposeVisible}
            onPurposeVisibleChange={changePurposeVisible}
          />
        </SideTabs>
        <FilterPanel applied={query} onApply={setQuery} onRecommend={recommend} hidden={leftTab !== 'filter'}
          availableDayTypes={meta?.availableDayTypes ?? null} />
        <RecommendationList result={recommendation} query={recommendQuery ?? query} loading={recommendLoading}
          error={recommendError} onMore={recommendMore} selectedBoardId={selectedId} onSelect={selectRecommended}
          onEditConditions={() => setLeftTab('filter')} hidden={leftTab !== 'result'} />
        <MapView
          provider={provider}
          onProviderChange={setProvider}
          boards={visibleBoards}
          selectedBoardId={selectedId}
          onSelectBoard={selectBoard}
          grid={meta?.grid ?? null}
          showGrid={showGrid}
          cells={cells}
          allCells={allCells}
          allCellsNotice={allCellsNotice}
          focus={focus}
          palette={palette}
          onViewChange={setView}
          stores={showStores ? currentStores?.stores ?? null : null}
          highlightCategory={highlightCategory}
        />
        <RightPanel
          board={currentBoard?.data ?? null}
          boardLoading={boardLoading}
          boardError={currentBoard?.error ?? null}
          population={currentPopulation?.data ?? null}
          loading={populationLoading}
          error={currentPopulation?.error ?? null}
          cellsError={cellsError}
          stores={currentStores}
          storesError={storesError}
          highlightCategory={highlightCategory}
          onSelectCategory={selectCategory}
          open={rightOpen}
          onToggle={() => setRightOpen((open) => !open)}
          recommendation={recommendation?.items.find((r) => r.boardId === selectedId) ?? null}
        />
      </main>

      <footer className="app-footer">
        {meta?.notice ?? '통신사 유동인구를 활용한 노출 가능성 추정이며 실제 광고 성과를 의미하지 않습니다.'}
      </footer>
    </div>
  )
}

export default App
