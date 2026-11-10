import argparse
import sys

from data_processing.pipeline import DataSetError, build, load


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="data-processing",
        description="FLOWSIGN 데이터 처리. data/raw/의 원본으로 data/generated_<모드>/ 세트를 만들고 DB에 적재한다.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    b = sub.add_parser("build", help="원본 확인 → 전처리·매핑 → 검증 후 data/generated_<모드>/ 생성")
    b.add_argument("mode", choices=["fake", "real"],
                   help="fake: 실제 게시대 + 가짜 통신사 데이터 / real: 실제 게시대 + 실제 통신사 원본")
    b.add_argument("--encoding", default="utf-8", help="통신사 원본 인코딩 (real 전용, 예: cp949)")
    b.add_argument("--load", action="store_true", help="생성·검증이 끝나면 이어서 DB에 적재")

    ld = sub.add_parser("load", help="검증을 통과한 data/generated_<모드>/ 세트를 DB에 적재 (전체 교체, 실패 시 롤백)")
    ld.add_argument("mode", choices=["fake", "real"])

    for p in (b, ld):
        p.add_argument("--container", default="telecom-postgres")
        p.add_argument("--db", default="telecom")
        p.add_argument("--user", default="telecom")

    args = parser.parse_args()
    db = {"container": args.container, "db": args.db, "user": args.user}
    try:
        if args.command == "build":
            if args.encoding != "utf-8" and args.mode == "fake":
                parser.error("--encoding은 real 모드에서만 씁니다.")
            build(args.mode, encoding=args.encoding)
            if args.load:
                load(args.mode, **db)
        elif args.command == "load":
            load(args.mode, **db)
    except DataSetError as e:
        print(f"중단: {e}", file=sys.stderr)
        sys.exit(1)
