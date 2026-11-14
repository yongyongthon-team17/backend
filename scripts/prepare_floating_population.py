"""경기도 유동인구 통계(시간대별·요일별 행정동)에서 용인시 구별 요약만 뽑아 seed CSV 2개로 저장한다.

사용법 (프로젝트 루트에서):
    python scripts/prepare_floating_population.py \
        --zone-source "C:/.../경기도_유동인구_시군구_행정동별_시간대별집계.csv" \
        --weekday-source "C:/.../데이터분석유동인구요일별행정동.csv" \
        --month 202411

입력은 CP949, 출력은 UTF-8이다. 원본(수십 MB)은 저장소에 넣지 않고 이 요약만 넣는다.
유동인구수는 제공처 설명대로 '월별·시간대별(요일별)·행정동별 일평균'이며, 행정동 값을 구별로 더한다.

출력 (src/main/resources/seed/):
  floating_zone.csv     district, zone, value   zone 1~10 = 시간대코드 TZ01~TZ10 (분석시스템 코드정보 기준)
                         TZ01 00:00~06:59, TZ02 07~08:59, TZ03 09~10:59, TZ04 11~12:59, TZ05 13~14:59,
                         TZ06 15~16:59, TZ07 17~18:59, TZ08 19~20:59, TZ09 21~22:59, TZ10 23:00~23:59
  floating_weekday.csv  district, day, value    day 0=월 … 6=일
"""
import argparse
import csv
import io
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "seed"
# 행정동코드 앞 5자리 → 구
DISTRICTS = {"41461": "처인구", "41463": "기흥구", "41465": "수지구"}
DAYS = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]


def read_rows(path: Path):
    raw = path.read_bytes()
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        text = raw.decode("cp949")
    return csv.DictReader(io.StringIO(text))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zone-source", type=Path, required=True)
    ap.add_argument("--weekday-source", type=Path, required=True)
    ap.add_argument("--month", default="202411", help="기준년월(YYYYMM)")
    args = ap.parse_args()

    zone = {}  # (구, 1~10) → 합계
    for r in read_rows(args.zone_source):
        if r["기준년월"] != args.month:
            continue
        district = DISTRICTS.get(r["행정동코드"][:5])
        code = r["시간대코드"]
        if district is None or not code.startswith("TZ"):
            continue
        key = (district, int(code[2:]))
        zone[key] = zone.get(key, 0.0) + float(r["유동인구수"])

    weekday = {}  # (구, 0~6) → 합계
    for r in read_rows(args.weekday_source):
        if r["기준년월"] != args.month:
            continue
        district = DISTRICTS.get(r["행정동코드"][:5])
        if district is None or r["요일코드"] not in DAYS:
            continue
        key = (district, DAYS.index(r["요일코드"]))
        weekday[key] = weekday.get(key, 0.0) + float(r["유동인구수"])

    expected_zone = len(DISTRICTS) * 10
    expected_weekday = len(DISTRICTS) * 7
    if len(zone) != expected_zone or len(weekday) != expected_weekday:
        raise SystemExit(f"기대 행 수와 다릅니다: 시간대 {len(zone)}/{expected_zone}, 요일 {len(weekday)}/{expected_weekday} "
                         f"(기준년월 {args.month} 자료가 있는지 확인하세요)")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with open(OUT_DIR / "floating_zone.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["district", "zone", "value"])
        for (district, z), v in sorted(zone.items()):
            w.writerow([district, z, round(v)])
    with open(OUT_DIR / "floating_weekday.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["district", "day", "value"])
        for (district, d), v in sorted(weekday.items()):
            w.writerow([district, d, round(v)])
    print(f"기준년월 {args.month}: floating_zone.csv {len(zone)}행, floating_weekday.csv {len(weekday)}행 저장")


if __name__ == "__main__":
    main()
