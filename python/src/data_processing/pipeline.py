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


def build(mode: str, raw_dir: Path = RAW_DIR, data_dir: Path = DATA_DIR, encoding: str = "utf-8",
          mapping_path: Path = LOCAL_MAPPING, log=print) -> dict:
    """원본 확인 → 임시 폴더에 세트 생성 → 검증 → 기존 세트와 교체. 리포트를 반환한다."""
    population_files = find_inputs(mode, raw_dir, mapping_path)
    final = set_dir(mode, data_dir)
    staging = final.with_name(final.name + ".building")
    shutil.rmtree(staging, ignore_errors=True)  # 지난번에 실패하고 남은 임시 폴더
    staging.mkdir(parents=True)

    try:
        board = convert_board_csv(raw_dir / BOARD_RAW, staging / "board.csv")
        log(f"[1] 게시대 변환: {board['boards']}개 {board['by_city']} (동 없음 {board['dong_missing']}, "
            f"금액 미제공 {board['fee_missing']})")

        if mode == "fake":
            n_cells, n_rows = generate_fake_csv(staging / POPULATION_FAKE, staging / "board.csv")
            log(f"[2] 가짜 통신사 데이터 생성: Cell {n_cells}개, {n_rows}행")
            inputs, mapping = [staging / POPULATION_FAKE], None
        else:
            log(f"[2] 통신사 원본 {len(population_files)}개: {[p.name for p in population_files]}")
            inputs, mapping = population_files, mapping_path

        report = preprocess_csv(inputs, staging, encoding=encoding, mapping_path=mapping, dataset=mode)
        log(f"[3] 전처리: population_fact {report['rows_written']}행, cell {report['cells']}개, "
            f"버린 행 {report['rows_dropped']}, UNKNOWN {report['unknown_counts']}")

        mapped = run_mapping(staging, staging / "board.csv")
        log(f"[4] 매핑: {mapped}")

        validate_set(staging, mode)
        log("[5] 세트 검증 통과")
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
    log(f"완료 → {final}")
    return report


def load(mode: str, data_dir: Path = DATA_DIR, log=print, **db) -> dict[str, int]:
    """검증을 통과한 세트만 DB에 적재한다. 검증 실패 시 DB는 건드리지 않는다."""
    directory = set_dir(mode, data_dir)
    validate_set(directory, mode)
    label = "실제 통신사 데이터" if mode == "real" else "가짜 통신사 데이터"
    log(f"{label} 세트({directory.name})를 적재합니다. 기존 DB 데이터는 성공 시에만 교체됩니다.")
    counts = load_set(directory, **db)
    for table, n in counts.items():
        log(f"  {table}: {n}행")
    if mode == "real":
        log("※ backend application.yml의 flowsign.dataset.synthetic을 false로 바꾸세요.")
    else:
        log("※ backend application.yml의 flowsign.dataset.synthetic이 true인지 확인하세요.")
    return counts
