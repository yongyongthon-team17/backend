"""국토교통부 전국 버스정류장 위치정보 CSV를 용인시 정류장만 추려 seed/bus_stops.csv로 저장한다.

사용법 (프로젝트 루트에서):
    python scripts/prepare_bus_stops.py "C:/Users/me/Downloads/국토교통부_전국 버스정류장 위치정보_20261020.csv"

옵션:
    --include-neighbors  도시명이 아니라 시설 좌표 범위(위도 36.99~37.51, 경도 126.89~127.51)로 거른다.
                         용인 경계의 시설이 인접 시(화성·성남·수원 등) 정류장을 놓치지 않게 하려면 켠다.

입력은 CP949, 출력은 UTF-8이다. 정류장번호로 중복을 제거한다.
출력 컬럼: stop_id, name, latitude, longitude, collected_at
"""
import argparse
import csv
import io
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "seed" / "bus_stops.csv"
CITY_KEYWORD = "용인시"
# SeedBuilder의 시설 좌표 범위(37.0~37.5, 126.9~127.5)에 0.01도 여유를 둔 사각형
LAT_MIN, LAT_MAX, LNG_MIN, LNG_MAX = 36.99, 37.51, 126.89, 127.51


def read_rows(path: Path):
    raw = path.read_bytes()
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        text = raw.decode("cp949")
    return csv.DictReader(io.StringIO(text))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source", type=Path, help="전국 버스정류장 위치정보 CSV")
    ap.add_argument("--include-neighbors", action="store_true", help="도시명 대신 좌표 범위로 거른다")
    args = ap.parse_args()

    seen = set()
    kept = []
    skipped_bad_coord = 0
    for r in read_rows(args.source):
        stop_id = (r.get("정류장번호") or "").strip()
        try:
            lat = float(r["위도"])
            lng = float(r["경도"])
        except (KeyError, TypeError, ValueError):
            skipped_bad_coord += 1
            continue
        if not stop_id or stop_id in seen or lat == 0 or lng == 0:
            skipped_bad_coord += 1
            continue
        if args.include_neighbors:
            inside = LAT_MIN <= lat <= LAT_MAX and LNG_MIN <= lng <= LNG_MAX
        else:
            inside = CITY_KEYWORD in (r.get("도시명") or "")
        if not inside:
            continue
        seen.add(stop_id)
        kept.append((stop_id, (r.get("정류장명") or "").strip(), lat, lng, (r.get("정보수집일") or "").strip()))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["stop_id", "name", "latitude", "longitude", "collected_at"])
        w.writerows(kept)
    print(f"saved {len(kept)} stops -> {OUT} (좌표 오류·중복 제외 {skipped_bad_coord}건)")


if __name__ == "__main__":
    main()
