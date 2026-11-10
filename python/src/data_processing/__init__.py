import argparse
from pathlib import Path

from data_processing.board import convert_board_csv
from data_processing.fake_data import cells_for_size, generate_fake_csv
from data_processing.load import load_all
from data_processing.mapping import run_mapping
from data_processing.preprocess import preprocess_csv


def main() -> None:
    parser = argparse.ArgumentParser(prog="data-processing", description="FLOWSIGN 데이터 처리")
    sub = parser.add_subparsers(dest="command", required=True)

    pre = sub.add_parser("preprocess", help="유동인구 원본 CSV 정제")
    pre.add_argument("input", type=Path)
    pre.add_argument("output_dir", type=Path, help="population_fact.csv, cell.csv, preprocess_report.json 저장 폴더")
    pre.add_argument("--encoding", default="utf-8", help="원본 인코딩 (예: utf-8, cp949)")
    pre.add_argument("--chunksize", type=int, default=1_000_000)
    pre.add_argument("--mapping", type=Path, help="원본 매핑 파일 (기본: raw_mapping.json, 없으면 예시 파일)")

    board = sub.add_parser("board", help="게시대 원본 CSV(공공데이터포털)를 표준 board.csv로 변환 (대상 3개 시만)")
    board.add_argument("input", type=Path)
    board.add_argument("output", type=Path)
    board.add_argument("--encoding", default="cp949", help="원본 인코딩 (기본: cp949)")

    fake = sub.add_parser("fake-data", help="원본 형식의 가짜 CSV 생성")
    fake.add_argument("output", type=Path)
    fake.add_argument("--size-mb", type=int, default=300, help="목표 파일 크기(MB, 근사)")
    fake.add_argument("--cells", type=int, help="Cell 수 직접 지정 (지정하면 --size-mb 무시)")
    fake.add_argument("--seed", type=int, default=42)
    fake.add_argument("--mapping", type=Path, help="원본 매핑 파일 (기본: raw_mapping.json, 없으면 예시 파일)")
    fake.add_argument("--boards", type=Path, help="표준 board.csv. 주면 그 게시대들의 3×3 Cell만 생성 (--size-mb·--cells 무시)")

    mapping = sub.add_parser("map", help="게시대 ↔ Cell 3×3 매핑, board_population·grid_spec 생성")
    mapping.add_argument("processed_dir", type=Path, help="전처리 결과 폴더 (결과 CSV도 여기에 저장)")
    mapping.add_argument("board_csv", type=Path)

    load = sub.add_parser("load", help="표준 CSV를 PostgreSQL에 전체 재적재 (docker 컨테이너의 psql 사용)")
    load.add_argument("processed_dir", type=Path)
    load.add_argument("board_csv", type=Path)
    load.add_argument("--container", default="telecom-postgres")
    load.add_argument("--db", default="telecom")
    load.add_argument("--user", default="telecom")

    args = parser.parse_args()
    if args.command == "preprocess":
        report = preprocess_csv(args.input, args.output_dir, chunksize=args.chunksize, encoding=args.encoding,
                                mapping_path=args.mapping)
        print(f"정제 완료: population_fact {report['rows_written']}행, cell {report['cells']}개 → {args.output_dir}")
        print(f"버린 행: {report['rows_dropped']}, UNKNOWN: {report['unknown_counts']}")
    elif args.command == "board":
        report = convert_board_csv(args.input, args.output, encoding=args.encoding)
        print(f"게시대 변환 완료: {report['boards']}개 {report['by_city']} → {args.output}")
        print(f"동 없음: {report['dong_missing']}, 금액 미제공: {report['fee_missing']}")
    elif args.command == "fake-data":
        n_cells = args.cells or cells_for_size(args.size_mb * 1024 * 1024)
        cells, rows = generate_fake_csv(args.output, n_cells, seed=args.seed, mapping_path=args.mapping,
                                        board_csv=args.boards)
        print(f"가짜 데이터 생성: Cell {cells}개, {rows}행 → {args.output}")
    elif args.command == "map":
        print(f"매핑 완료: {run_mapping(args.processed_dir, args.board_csv)}")
    elif args.command == "load":
        for table, result in load_all(args.processed_dir, args.board_csv, args.container, args.db, args.user).items():
            print(f"{table}: {result}")
