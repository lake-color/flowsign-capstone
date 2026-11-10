"""데이터 세트 단위 실행: 원본 확인 → 생성(전처리·매핑) → 검증 → 교체 → (요청 시) DB 적재.

전체 흐름은 이 파일의 build()·load()에 순서대로 있다. 다른 파일은 여기서 부르는 부품이다.

부품 지도 (명령: data-processing build fake|real [--load], load fake|real — __init__.py)
  단계                 파일             결과 (data/generated_<모드>/)
  0. 원본 확인          pipeline.py      find_inputs(): 빠진 원본·매핑 파일을 모아 중단
  1. 게시대 변환        board.py         board.csv  (raw/board_raw.csv에서 대상 3개 시만)
  2. 통신사 데이터      fake_data.py     population_fake.csv  (fake 모드에서만. real은 raw/population_raw_*.csv)
  3. 전처리            preprocess.py    cell.csv, population_fact.csv, preprocess_report.json
  4. 매핑              mapping.py       board_cell.csv, board_population.csv, grid_spec.csv
  5. 세트 검증          pipeline.py      validate_set(): 파일·출처(dataset)·ID 일치
  6. 교체              pipeline.py      <세트>.building → <세트>
  7. DB 적재           load.py          load(): 검증 후 테이블 6개를 트랜잭션 하나로 교체

폴더는 모드로 고정한다. 사용자는 경로를 입력하지 않으므로 다른 세트 폴더에 쓰는 실수가 생기지 않는다.
  data/raw/             사람이 넣는 실제 원본만. 어떤 명령도 여기에 쓰지 않는다.
  data/generated_real/  실제 게시대 + 실제 통신사 원본으로 만든 세트
  data/generated_fake/  실제 게시대 + 가짜 통신사 데이터로 만든 세트

안전 규칙:
  - 필요한 원본이 없으면 무엇이 없는지 출력하고 중단한다. 대신 가짜 데이터를 만들거나 쓰지 않는다.
  - 세트는 임시 폴더(<세트>.building)에 만들고, 검증까지 통과해야 기존 세트 폴더와 바꾼다.
    중간에 실패하면 임시 폴더만 지우고 기존 세트는 그대로 둔다.
  - DB 적재는 검증을 통과한 세트만 하고, 적재 자체도 트랜잭션 하나로 한다(load.py).
"""

import json
import re
import shutil
import time
import unicodedata
from pathlib import Path

import pandas as pd

from data_processing.board import convert_board_csv
from data_processing.fake_data import generate_fake_csv
from data_processing.load import LOAD_TABLES, load_set
from data_processing.mapping import run_mapping
from data_processing.preprocess import LOCAL_MAPPING, preprocess_csv

DATA_DIR = Path(__file__).resolve().parents[3] / "data"
RAW_DIR = DATA_DIR / "raw"
SET_DIR_NAMES = {"real": "generated_real", "fake": "generated_fake"}

BOARD_RAW = "board_raw.csv"
POPULATION_RAW = re.compile(r"population_raw_(\d+)\.csv")
POPULATION_FAKE = "population_fake.csv"
REPORT = "preprocess_report.json"
SET_FILES = [f"{t}.csv" for t in LOAD_TABLES] + [REPORT]


class DataSetError(Exception):
    """원본 누락, 세트 검증 실패 등 실행을 멈춰야 하는 문제."""


def set_dir(mode: str, data_dir: Path = DATA_DIR) -> Path:
    if mode not in SET_DIR_NAMES:
        raise DataSetError(f"알 수 없는 모드: {mode} (real 또는 fake)")
    return data_dir / SET_DIR_NAMES[mode]


def find_inputs(mode: str, raw_dir: Path = RAW_DIR, mapping_path: Path = LOCAL_MAPPING) -> list[Path]:
    """모드에 필요한 원본을 확인한다. 없으면 빠진 파일을 모두 모아 한 번에 알려 주고 중단한다.

    fake: 게시대 원본만 필요. 통신사 원본은 읽지 않는다.
    real: 게시대 원본 + population_raw_1.csv부터 번호가 이어지는 통신사 원본 + 실제 매핑 파일.
    반환: real이면 통신사 원본 목록(번호순), fake면 빈 목록.
    """
    problems = []
    if not (raw_dir / BOARD_RAW).is_file():
        problems.append(f"게시대 원본이 없습니다: {raw_dir / BOARD_RAW}")
    if mode == "fake":
        if problems:
            raise DataSetError("\n".join(problems))
        return []

    numbered, unexpected = {}, []
    for p in sorted(raw_dir.glob("population_raw*")) if raw_dir.is_dir() else []:
        m = POPULATION_RAW.fullmatch(p.name)
        if m:
            numbered[int(m.group(1))] = p
        else:
            unexpected.append(p.name)
    if not numbered:
        problems.append(f"통신사 유동인구 원본이 없습니다: {raw_dir / 'population_raw_1.csv'} (2개 이상이면 _2, _3 …)")
    else:
        gaps = [n for n in range(1, max(numbered) + 1) if n not in numbered]
        problems += [f"통신사 유동인구 원본 번호가 비어 있습니다: {raw_dir / f'population_raw_{n}.csv'}" for n in gaps]
    if unexpected:
        problems.append(f"이름 규칙(population_raw_<번호>.csv)에 맞지 않는 파일이 있습니다: {unexpected}")
    if not mapping_path.is_file():
        problems.append(f"실제 원본 매핑 파일이 없습니다: {mapping_path} (예시 파일로 대신하지 않습니다)")
    if problems:
        raise DataSetError("\n".join(problems))
    return [numbered[n] for n in sorted(numbered)]


def validate_set(directory: Path, mode: str) -> dict:
    """세트 폴더가 온전하고 모드와 출처가 맞는지 확인한다. 통과하면 리포트를 반환한다."""
    if not directory.is_dir():
        raise DataSetError(f"데이터 세트 폴더가 없습니다: {directory} (먼저 build {mode} 실행)")
    missing = [name for name in SET_FILES if not (directory / name).is_file()]
    if missing:
        raise DataSetError(f"{directory.name}에 파일이 없습니다: {missing}")

    report = json.loads((directory / REPORT).read_text(encoding="utf-8"))
    inputs = report.get("input_files") or []
    problems = []
    if report.get("dataset") != mode:
        problems.append(f"리포트의 dataset이 '{report.get('dataset')}'입니다 (기대: '{mode}')")
    has_fake = (directory / POPULATION_FAKE).exists()
    if mode == "fake" and (inputs != [POPULATION_FAKE] or not has_fake):
        problems.append(f"가짜 세트인데 입력이 {inputs}입니다 (기대: ['{POPULATION_FAKE}'])")
    if mode == "real":
        if has_fake:
            problems.append(f"실제 세트 폴더에 {POPULATION_FAKE}가 있습니다")
        if not inputs or not all(POPULATION_RAW.fullmatch(name) for name in inputs):
            problems.append(f"실제 세트인데 입력이 통신사 원본이 아닙니다: {inputs}")

    # 세트 안의 파일끼리 서로 맞는지 (다른 세트 파일이 섞이면 ID가 어긋난다)
    boards = pd.read_csv(directory / "board.csv", usecols=["board_id"])["board_id"]
    cells = pd.read_csv(directory / "cell.csv", usecols=["cell_id"], dtype=str)["cell_id"]
    links = pd.read_csv(directory / "board_cell.csv", usecols=["board_id", "cell_id"], dtype={"cell_id": str})
    if boards.empty or boards.duplicated().any():
        problems.append("board.csv가 비어 있거나 board_id가 중복됩니다")
    if not links["board_id"].isin(boards).all():
        problems.append("board_cell.csv에 board.csv에 없는 게시대가 있습니다")
    if not links["cell_id"].isin(cells).all():
        problems.append("board_cell.csv에 cell.csv에 없는 Cell이 있습니다")
    if problems:
        raise DataSetError(f"{directory.name} 검증 실패:\n" + "\n".join(problems))
    return report


MODE_LABEL = {"real": "실제(real)", "fake": "가짜(fake)"}
UNKNOWN_LABEL = {"day_type": "주중/주말", "gender": "성별", "age_group": "연령", "time_slot": "시간대"}
LINE = "=" * 64


def _width(text: str) -> int:
    """콘솔 표시 폭 (한글 등 전각 문자는 2칸)."""
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in text)


def _kv(label: str, value, note: str = "") -> str:
    """'  라벨      값   (설명)' 한 줄. 라벨 폭을 맞춘다."""
    return f"  {label}{' ' * max(1, 24 - _width(label))}{value}" + (f"   ({note})" if note else "")


def _n(value: int) -> str:
    return f"{value:,}"


def _header(log, title: str, *lines: str) -> None:
    log(LINE)
    log(f" {title}")
    for line in lines:
        log(f" {line}")
    log(LINE)


def _footer(log, *lines: str) -> None:
    log("")
    log(LINE)
    for line in lines:
        log(f" {line}")
    log(LINE)


def _elapsed(start: float) -> str:
    sec = int(time.monotonic() - start)
    return f"{sec // 60}분 {sec % 60}초" if sec >= 60 else f"{sec}초"


class _Steps:
    """단계 제목을 출력하고, 실패하면 어느 단계에서 멈췄는지 DataSetError로 알려 준다."""

    def __init__(self, log, total: int) -> None:
        self.log, self.total, self.current = log, total, ""

    def start(self, no: int, name: str) -> None:
        self.current = f"[{no}/{self.total}] {name}"
        self.log("")
        self.log(self.current)

    def fail(self, error: Exception) -> DataSetError:
        message = f"{self.current} 단계에서 오류: {error}"
        if isinstance(error, UnicodeDecodeError):
            message += "\n원본 인코딩이 UTF-8이 아닐 수 있습니다. --encoding cp949로 다시 실행해 보세요."
        return DataSetError(message)


def build(mode: str, raw_dir: Path = RAW_DIR, data_dir: Path = DATA_DIR, encoding: str = "utf-8",
          mapping_path: Path = LOCAL_MAPPING, log=print) -> dict:
    """원본 확인 → 임시 폴더에 세트 생성 → 검증 → 기존 세트와 교체. 리포트를 반환한다."""
    start = time.monotonic()
    final = set_dir(mode, data_dir)
    _header(log, f"FLOWSIGN 데이터 생성 · {MODE_LABEL[mode]} 모드", f"결과 폴더: {final}")
    steps = _Steps(log, 5)

    steps.start(0, "원본 확인")
    population_files = find_inputs(mode, raw_dir, mapping_path)
    log(_kv("게시대 원본", BOARD_RAW, "OK"))
    if mode == "real":
        log(_kv(f"통신사 원본 {len(population_files)}개", ", ".join(p.name for p in population_files), "OK"))
        log(_kv("매핑 파일", mapping_path.name, "OK"))
        log("  → 통신사 원본 개수가 실제로 받은 파일 수와 같은지 확인하세요.")
    else:
        log(_kv("통신사 원본", "사용 안 함 (가짜 데이터 생성)"))

    staging = final.with_name(final.name + ".building")
    shutil.rmtree(staging, ignore_errors=True)  # 지난번에 실패하고 남은 임시 폴더
    staging.mkdir(parents=True)
    try:
        steps.start(1, "게시대 변환")
        board = convert_board_csv(raw_dir / BOARD_RAW, staging / "board.csv")
        by_city = " · ".join(f"{city} {_n(n)}" for city, n in board["by_city"].items())
        log(_kv("게시대", f"{_n(board['boards'])}개", by_city))
        log(_kv("동 없음", f"{_n(board['dong_missing'])}개"))
        log(_kv("금액 미제공", f"{_n(board['fee_missing'])}개"))

        steps.start(2, "통신사 데이터")
        if mode == "fake":
            n_cells, n_rows = generate_fake_csv(staging / POPULATION_FAKE, staging / "board.csv")
            log(_kv("가짜 데이터 생성", f"Cell {_n(n_cells)}개, {_n(n_rows)}행", POPULATION_FAKE))
            inputs, mapping = [staging / POPULATION_FAKE], None
        else:
            log(_kv("실제 원본 사용", f"{len(population_files)}개 파일을 번호순으로 이어 읽음"))
            inputs, mapping = population_files, mapping_path

        steps.start(3, "전처리")
        report = preprocess_csv(inputs, staging, encoding=encoding, mapping_path=mapping, dataset=mode)
        dropped = report["rows_dropped"]
        log(_kv("population_fact", f"{_n(report['rows_written'])}행"))
        log(_kv("Cell", f"{_n(report['cells'])}개"))
        log(_kv("버린 행", f"{_n(sum(dropped.values()))}행",
                f"cell_id 없음 {_n(dropped['cell_id_null'])}, 기준월 없음 {_n(dropped['base_month_null'])}"))
        log(_kv("UNKNOWN(빈 분류값)", ", ".join(f"{UNKNOWN_LABEL[k]} {_n(v)}"
                                             for k, v in report["unknown_counts"].items())))

        steps.start(4, "매핑")
        mapped = run_mapping(staging, staging / "board.csv")
        log(_kv("9칸이 모두 있는 게시대", f"{_n(mapped['boards_with_9_cells'])} / {_n(mapped['boards'])}",
                "나머지는 주변에 데이터가 없는 칸 제외"))
        log(_kv("board_cell", f"{_n(mapped['board_cell_rows'])}행"))
        log(_kv("board_population", f"{_n(mapped['board_population_rows'])}행"))
        grid_note = "" if mapped["grid_status"] == "VERIFIED" else "지도에서 250m 격자선 없음. Cell 사각형·매핑은 영향 없음"
        log(_kv("격자 검증", mapped["grid_status"], grid_note))

        steps.start(5, "세트 검증")
        validate_set(staging, mode)
        log(_kv("파일·출처·ID 일치", "통과"))
    except DataSetError:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    except Exception as error:
        shutil.rmtree(staging, ignore_errors=True)
        raise steps.fail(error) from error
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise

    # 검증까지 끝난 뒤에만 기존 세트를 바꾼다.
    old = final.with_name(final.name + ".old")
    shutil.rmtree(old, ignore_errors=True)
    if final.exists():
        final.rename(old)
    staging.rename(final)
    shutil.rmtree(old, ignore_errors=True)
    next_step = f"load {mode}" if mode == "fake" else f"preprocess_report.json 확인 후 load {mode}"
    _footer(log, f"완료: {final}   (소요 {_elapsed(start)})", f"다음: {next_step}")
    return report


def load(mode: str, data_dir: Path = DATA_DIR, log=print, **db) -> dict[str, int]:
    """검증을 통과한 세트만 DB에 적재한다. 검증 실패 시 DB는 건드리지 않는다."""
    start = time.monotonic()
    directory = set_dir(mode, data_dir)
    target = f"{db.get('container', 'telecom-postgres')} / {db.get('db', 'telecom')}"
    _header(log, f"FLOWSIGN DB 적재 · {MODE_LABEL[mode]} 세트", f"{directory} → {target}")
    steps = _Steps(log, 2)

    steps.start(1, "세트 검증")
    validate_set(directory, mode)
    log(_kv("파일·출처·ID 일치", "통과"))

    steps.start(2, "적재 (트랜잭션 하나, 실패 시 전체 롤백)")
    try:
        counts = load_set(directory, **db)
    except Exception as error:
        raise DataSetError(f"{steps.current} 단계에서 오류 (기존 DB 데이터는 그대로): {error}") from error
    for table, n in counts.items():
        log(_kv(table, f"{_n(n)}행"))

    if mode == "real":
        note = "※ backend application.yml의 flowsign.dataset.synthetic을 false로 바꾸세요 (커밋하지 말 것)."
    else:
        note = "※ backend application.yml의 flowsign.dataset.synthetic이 true인지 확인하세요."
    _footer(log, f"완료   (소요 {_elapsed(start)})", note)
    return counts
