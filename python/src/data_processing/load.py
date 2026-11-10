"""데이터 세트 폴더의 표준 CSV를 PostgreSQL에 적재한다 (테이블은 Flyway migration이 이미 만든 상태).

로컬 PC에 psql을 설치하지 않아도 되도록 docker compose의 PostgreSQL 컨테이너 안의 psql을 쓴다.
CSV를 컨테이너 임시 폴더로 복사한 뒤, TRUNCATE와 모든 COPY, 세트 종류 기록을 **트랜잭션 하나**로 실행한다.
세트 종류(real/fake)는 dataset_info 테이블에 기록하고, 백엔드가 이 값을 읽어 응답에 표시한다.
중간에 하나라도 실패하면 전체가 롤백되어 기존 DB 데이터가 그대로 남는다.
복사한 임시 파일은 성공·실패와 관계없이 지운다.
"""

import re
import subprocess
import uuid
from pathlib import Path

# FK 순서대로 적재한다. 파일명 = 테이블명.csv
LOAD_TABLES = ["cell", "board", "population_fact", "board_cell", "board_population", "grid_spec"]


def _run(cmd: list[str], what: str) -> str:
    result = subprocess.run(cmd, capture_output=True)
    stdout = result.stdout.decode("utf-8", "replace")
    if result.returncode != 0:
        raise RuntimeError(f"{what} 실패:\n{result.stderr.decode('utf-8', 'replace').strip()}")
    return stdout


def load_set(set_dir: Path, dataset: str, container: str = "telecom-postgres",
             db: str = "telecom", user: str = "telecom") -> dict[str, int]:
    """set_dir의 CSV 6개로 테이블 6개를 한 트랜잭션 안에서 교체하고 세트 종류(dataset)를 기록한다.

    테이블별 적재 행 수를 반환한다.
    """
    if dataset not in ("real", "fake"):
        raise ValueError(f"dataset은 real 또는 fake여야 합니다: {dataset}")
    files = {table: set_dir / f"{table}.csv" for table in LOAD_TABLES}
    missing = [p.name for p in files.values() if not p.exists()]
    if missing:
        raise FileNotFoundError(f"적재할 CSV가 없습니다: {missing}")

    remote_dir = f"/tmp/flowsign_load_{uuid.uuid4().hex}"
    lines = ["TRUNCATE " + ", ".join(["dataset_info", *reversed(LOAD_TABLES)]) + ";"]
    for table, path in files.items():
        with path.open(encoding="utf-8") as f:
            header = f.readline().strip()
        lines.append(f"\\copy {table} ({header}) FROM '{remote_dir}/{path.name}' WITH (FORMAT csv, HEADER true)")
    lines.append(f"INSERT INTO dataset_info (id, dataset) VALUES (1, '{dataset}');")
    script = set_dir / ".load.sql"
    script.write_text("\n".join(lines) + "\n", encoding="utf-8")

    try:
        _run(["docker", "exec", container, "mkdir", "-p", remote_dir], "컨테이너 임시 폴더 생성")
        for path in [*files.values(), script]:
            _run(["docker", "cp", str(path), f"{container}:{remote_dir}/{path.name}"], f"{path.name} 복사")
        out = _run(["docker", "exec", container, "psql", "-v", "ON_ERROR_STOP=1", "--single-transaction",
                    "-U", user, "-d", db, "-f", f"{remote_dir}/{script.name}"], "DB 적재(전체 롤백됨)")
        _run(["docker", "exec", container, "psql", "-U", user, "-d", db, "-c", "ANALYZE"], "ANALYZE")
    finally:
        script.unlink(missing_ok=True)
        subprocess.run(["docker", "exec", container, "rm", "-rf", remote_dir], capture_output=True)

    counts = [int(n) for n in re.findall(r"^COPY (\d+)$", out, flags=re.MULTILINE)]
    return dict(zip(LOAD_TABLES, counts))
