"""표준 CSV를 PostgreSQL에 적재한다 (psql copy, 테이블은 Flyway migration이 이미 만든 상태).

로컬 PC에 psql을 설치하지 않아도 되도록 docker compose의 PostgreSQL 컨테이너 안의 psql을 쓴다.
CSV는 표준 입력으로 넘기므로(COPY ... FROM STDIN) 컨테이너에 파일을 복사할 필요가 없다.
기존 데이터는 모두 지우고 다시 넣는다(개발·데모용 전체 재적재).
"""

import subprocess
from pathlib import Path

# FK 순서대로 적재한다. (테이블, CSV 파일명)
LOAD_ORDER = [
    ("cell", "cell.csv"),
    ("board", "board.csv"),
    ("population_fact", "population_fact.csv"),
    ("board_cell", "board_cell.csv"),
    ("board_population", "board_population.csv"),
    ("grid_spec", "grid_spec.csv"),
]


def _psql(container: str, db: str, user: str, sql: str, stdin_path: Path | None = None) -> str:
    cmd = ["docker", "exec", "-i", container, "psql", "-v", "ON_ERROR_STOP=1", "-U", user, "-d", db, "-c", sql]
    if stdin_path is None:
        result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    else:
        with stdin_path.open("rb") as f:
            result = subprocess.run(cmd, stdin=f, capture_output=True)
        result.stdout = result.stdout.decode("utf-8", "replace")
        result.stderr = result.stderr.decode("utf-8", "replace")
    if result.returncode != 0:
        raise RuntimeError(f"psql 실패: {sql}\n{result.stderr.strip()}")
    return result.stdout.strip()


def load_all(data_dir: Path, board_csv: Path, container: str = "telecom-postgres",
             db: str = "telecom", user: str = "telecom") -> dict[str, str]:
    """data_dir의 표준 CSV(board는 board_csv)를 비우고 다시 적재한다. 테이블별 COPY 결과를 반환한다."""
    files = {table: (board_csv if table == "board" else data_dir / name) for table, name in LOAD_ORDER}
    missing = [str(p) for p in files.values() if not p.exists()]
    if missing:
        raise FileNotFoundError(f"적재할 CSV가 없습니다: {missing}")

    tables = ", ".join(table for table, _ in reversed(LOAD_ORDER))
    _psql(container, db, user, f"TRUNCATE {tables}")

    results = {}
    for table, _ in LOAD_ORDER:
        path = files[table]
        with path.open(encoding="utf-8") as f:
            header = f.readline().strip()
        copy = f"\\copy {table} ({header}) FROM STDIN WITH (FORMAT csv, HEADER true)"
        results[table] = _psql(container, db, user, copy, stdin_path=path)
    _psql(container, db, user, "ANALYZE")
    return results
