import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from './api'
import BoardDetail from './components/BoardDetail'
import FilterPanel from './components/FilterPanel'
import { DEFAULT_QUERY } from './constants'
import MapView, { type MapProvider } from './components/MapView'
import type { Board, BoardPopulation, Bounds, CellItem, MapCell, Meta, PopulationQuery } from './types'
import './App.css'

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

  const selectBoard = useCallback((id: string) => setSelectedId(id), [])

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
        <h1>FLOWSIGN</h1>
        <p>유동인구 데이터 기반 옥외광고 게시대 추천 서비스 · 데모 초안</p>
      </header>

      {loadError && <div className="banner error">데이터를 불러오지 못했습니다: {loadError}</div>}

      <main className="app-body">
        <FilterPanel applied={query} onApply={setQuery} />
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
          onViewChange={setView}
        />
        <BoardDetail board={board} population={population} loading={detailLoading} error={detailError} />
      </main>

      <footer className="app-footer">
        {meta?.notice ?? '통신사 유동인구를 활용한 노출 가능성 추정이며 실제 광고 성과를 의미하지 않습니다.'}
      </footer>
    </div>
  )
}

export default App
