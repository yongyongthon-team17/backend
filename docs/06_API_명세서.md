# API 명세서

v0.3 · v0.2 원본에 B03 정정(한석영, 2026-11-08 노션 「B03 api 명세서」)을 반영하고, B03 이후 머지된 A03·A04·A05 구현 결정을 합친 통합본입니다.

- 기준: DB 스키마는 10번(데이터테이블 v2), 공통 필드·envelope는 07번. 시간은 분 단위(open_min/close_min, 24:00=1440), 요일은 0=월 … 6=일.
- 근거 표기: **[B03]** = B03 정정 초안 내용, **[구현]** = main에 머지된 코드 기준(PR #8 등), **[미정]** = 팀 결정 필요.
- ‘검토 중’은 API 계약 합의 전이라는 뜻입니다. 구현·테스트 완료를 의미하지 않습니다.

## API 목록

| API명 | API ID | Method | Endpoint | 기능 그룹 | 구현 범위 | 구현 상태 |
|---|---|---|---|---|---|---|
| 검색 선택지 조회 | A01 | GET | /api/v1/facility-search-options | 검색 선택지 | 지원 제안 | 구현 |
| 시간대별 시설 검색 | A02 | GET | /api/v1/facilities | 시설 검색 | P0 핵심 | 구현 |
| 시설 상세 및 공간별 운영시간 | A03 | GET | /api/v1/facilities/{facilityId} | 시설 상세 | P0 핵심 | 구현 |
| 공간별 불일치 분석 및 AI 개선안 | A04 | POST | /api/v1/improvement-analyses | AI 분석 | P0 핵심 | 구현 |
| 시설 교통 접근성 조회 | A05 | GET | /api/v1/facilities/{facilityId}/transport-accessibility | 교통 접근성 | P1 조건부 | 구현 |

## 공통 문서

- 공통 규칙·응답 타입 → `07_API_공통규칙_응답타입.md`
- DB 매핑·보완 및 논의 사항 → `08_DB_매핑_보완_논의사항.md`
- 외부 연동·요구사항 추적·검증 기준 → `09_외부연동_요구사항추적_검증기준.md`

## 공통 정정 요약 (07·10번에 반영 필요) [B03]

07·10번 본문은 아직 v0.2입니다. 아래가 우선합니다.

| 대상 | v0.2 | 정정 |
|---|---|---|
| 07 §3.3-7, 10 §8 시간 판정 | 10 §8 "겹치면 포함", 07 §3.3-7 "복수 구간이면 UNKNOWN" | 같은 공간·요일의 OPEN 구간(interval_seq)을 합쳐 희망 구간 [startTime, endTime)이 빈틈없이 덮이면 포함. 맞닿은 구간은 이어진 것으로 본다. 휴게시간에 걸치면 UNAVAILABLE. 공간끼리 합쳐서 덮은 것으로 보지 않는다 |
| 07 §3.3-2 시각 | DBMS TIME | 자정부터의 분(SMALLINT), 24:00=1440. API는 HH:mm |
| 07 §3.3-5·6 | is_closed 기준 | day_state(OPEN / CLOSED / UNKNOWN). 시간이 없다고 휴관으로 추측하지 않음 |
| 07 §3.7 DaySchedule | openTime / closeTime 한 쌍 | `intervals: [{openTime, closeTime}]` 배열 (아래 표) |
| 07 §3.7 spaceLabel | "단일 공간" | facility_space.space_name 그대로(종합자료실, 열람실, 시설 전체) |
| 07 §3.2 운영행 없는 시설 | 가상 DEFAULT/UNKNOWN 구성 | 사용 안 함. 모든 시설은 facility_space 1행 이상. ALWAYS_OPEN만 DEFAULT 행이 없으면 ‘시설 전체’ 가상 공간 |
| 07 §3.5 오류 코드 | 404는 FACILITY_NOT_FOUND만 | 존재하지 않는 경로는 404 NOT_FOUND 추가. INVALID_REQUEST에서 좌표 형식 삭제 |

| DaySchedule 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| dayOfWeek | integer | 예 | 아니오 | 0~6 |
| state | string(enum) | 예 | 아니오 | OPEN / CLOSED / UNKNOWN |
| intervals | TimeInterval[] | 예 | 아니오 | OPEN이면 1개 이상(interval_seq 순서), CLOSED/UNKNOWN이면 [] |
| TimeInterval.openTime | string(time) | 예 | 아니오 | HH:mm |
| TimeInterval.closeTime | string(time) | 예 | 아니오 | HH:mm (24:00 허용) |

미운영 구간(unavailableIntervals)의 원소는 `{startTime, endTime}`이다. 코드 타입명은 Gap, 07은 Interval로 이름만 다르다 [미정: 이름 통일].

---

## A01. 검색 선택지 조회

- `GET /api/v1/facility-search-options`
- 목적: 실제 facility_type의 ID와 표시값 제공, 지역·요일 코드 동기화.
- Path / Query / Body: 없음. 인증: 추가 논의 필요.
- 응답: 200 + 공통 envelope.

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| data.facilityTypes | FacilityTypeOption[] | 예 | 아니오 | 실제 DB에 등록된 유형 |
| FacilityTypeOption.typeId | integer | 예 | 아니오 | DB가 부여한 ID. 하드코딩하지 않는다 |
| FacilityTypeOption.name | string | 예 | 아니오 | 유형명 |
| FacilityTypeOption.defaultImageUrl | string | 예 | 예 | facility_type.default_image 그대로(상대 경로 `/images/default/*.png`) |
| data.districts | string[] | 예 | 아니오 | 수지구/기흥구/처인구 |
| data.daysOfWeek | DayOption[] | 예 | 아니오 | 요일 선택지 |
| DayOption.value | integer | 예 | 아니오 | 0~6 |
| DayOption.label | string | 예 | 아니오 | 요일명 |
| data.defaultDayOfWeek | integer | 예 | 아니오 | 응답 시점 한국 요일 |
| data.timeZone | string | 예 | 아니오 | Asia/Seoul |

```json
{
  "data": {
    "facilityTypes": [
      { "typeId": 1, "name": "도서관", "defaultImageUrl": "/images/default/library.png" },
      { "typeId": 2, "name": "생활체육시설", "defaultImageUrl": "/images/default/sports.png" }
    ],
    "districts": ["수지구", "기흥구", "처인구"],
    "daysOfWeek": [
      { "value": 0, "label": "월" },
      { "value": 1, "label": "화" },
      { "value": 2, "label": "수" },
      { "value": 3, "label": "목" },
      { "value": 4, "label": "금" },
      { "value": 5, "label": "토" },
      { "value": 6, "label": "일" }
    ],
    "defaultDayOfWeek": 6,
    "timeZone": "Asia/Seoul"
  },
  "meta": { "requestId": "req-demo-001", "generatedAt": "2026-11-01T22:45:00+09:00", "warnings": [] }
}
```

정정 내용 [B03]
- DB에는 6개 유형이 있다: 도서관, 생활체육시설, 주민자치센터, 보건소, 평생학습관, 문화센터. 도서관과 작은도서관은 ‘도서관’ 하나로 합쳐 적재했다.
- chip_group은 응답에 포함하지 않는다(코드 주석: "명세 개정 전까지 포함하지 않는다"). 04와 10번의 칩 구성이 서로 달라 [미정].
- defaultImageUrl의 기준 URL과 실제 이미지 파일 위치는 [미정]. 현재 백엔드는 정적 이미지를 서빙하지 않는다(그 경로로 요청하면 404 NOT_FOUND).

| typeId(현재 DB) | name | chip_group(응답 미포함) |
|---|---|---|
| 1 | 도서관 | 도서관 |
| 2 | 생활체육시설 | 체육시설 |
| 3 | 주민자치센터 | 복지시설 |
| 4 | 보건소 | 복지시설 |
| 5 | 평생학습관 | 문화공간 |
| 6 | 문화센터 | 문화공간 |

상태코드: 200 정상, 503 DATA_UNAVAILABLE, 나머지는 07 §3.5 공통.

---

## A02. 시간대별 시설 검색

- `GET /api/v1/facilities`
- 목적: 선택 요일·시간 전체에 운영하는 공간을 가진 시설 조회. 시설은 중복 없이 카드/마커 하나로 제공.
- Path: 없음. Body: 없음. 인증: 추가 논의 필요.

#### 정정 내용 [B03]

| # | 항목 | v0.2 | 정정 |
|---|---|---|---|
| 1 | 거리 쿼리 | latitude/longitude 지원 | 미지원. 전달해도 무시하며 한쪽만 줘도 400이 아님 |
| 2 | distanceMeters | 좌표가 있으면 계산 | 항상 null |
| 3 | selectedSchedule | openTime / closeTime | intervals 배열 (A03 weeklyHours도 같은 모양) |
| 4 | spaceLabel | 종합자료실/열람실/단일 공간 | space_name 그대로(‘시설 전체’ 등) |
| 5 | unavailableIntervals | 미운영 구간 | A02에서는 항상 [] |
| 6 | criteria.facilityTypeIds | 언급 없음 | 중복 제거, 생략하면 [] |
| 7 | ALWAYS_OPEN | 가상 DEFAULT 공간 | intervals=[{00:00, 24:00}], basis=ACCESS_TYPE. DEFAULT 행이 없으면 ‘시설 전체’ 가상 공간 |
| 8 | unknownSpaceCount | UNKNOWN 제외 공간 수 | 매칭된 시설의 UNKNOWN 공간도 세고 CLOSED는 세지 않음 |
| 9 | 경고 | UNKNOWN_HOURS_EXCLUDED 등 | WEEKLY_SCHEDULE_ONLY만 항상 |
| 10 | 오류 검증 순서 | 없음 | INVALID_REQUEST(요일·구·typeId·필수 누락) → INVALID_TIME_RANGE(시간 형식·범위) |

#### Query

| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| keyword | string | 아니오 | 시설명 OR 주소 포함(대소문자 무시). `%` `_` `\`는 글자 그대로 검색. 양끝 공백 제거, 공백만이면 null |
| district | string(enum) | 아니오 | 수지구/기흥구/처인구. 생략하면 전체. 그 외 400 |
| facilityTypeIds | integer[] | 아니오 | 반복 전달. 생략/[]=전체. 없는 typeId가 하나라도 있으면 400 |
| dayOfWeek | integer | 예 | 0~6 |
| startTime | string | 예 | HH:mm |
| endTime | string | 예 | HH:mm, 24:00 허용, startTime보다 늦어야 함 |

한글 값은 UTF-8 퍼센트 인코딩으로 보낸다. UTF-8이 아닌 바이트(예: CP949)는 현재 500 INTERNAL_ERROR가 난다 [미정: 400 처리].

```
GET /api/v1/facilities?district=%EC%88%98%EC%A7%80%EA%B5%AC&facilityTypeIds=1&dayOfWeek=1&startTime=19%3A00&endTime=21%3A00
Accept: application/json
```

#### 응답

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| data.criteria | Criteria | 예 | 아니오 | 여섯 필드 정규화 |
| data.items | Item[] | 예 | 아니오 | facilityId 숫자 오름차순. 없으면 [] |
| data.totalCount | integer | 예 | 아니오 | items.length |
| data.matchedSpaceCount | integer | 예 | 아니오 | 모든 availableSpaces 길이의 합 |
| data.unknownSpaceCount | integer | 예 | 아니오 | 운영시간을 알 수 없는 공간 수(행 없음·UNKNOWN). CLOSED는 세지 않음 |
| Item.facilityId | string | 예 | 아니오 | BIGINT 10진수 문자열 |
| Item.typeId / typeName | integer / string | 예 | 아니오 | facility_type |
| Item.district / accessType | string(enum) | 예 | 아니오 | 구 / OPEN_HOURS·RESERVATION·ALWAYS_OPEN |
| Item.name / address | string | 예 | 아니오 | 시설명 / 주소 |
| Item.imageUrl | string | 예 | 예 | default_image 그대로(상대 경로) |
| Item.coordinates | object | 예 | 아니오 | latitude / longitude (WGS84) |
| Item.distanceMeters | number | 예 | 예 | 항상 null |
| Item.availableSpaces | SpaceAvailability[] | 예 | 아니오 | 시간에 맞는 공간만, 시설당 1개 이상. GENERAL → READING → DEFAULT |

| SpaceAvailability 필드 | 타입 | 설명 |
|---|---|---|
| spaceType | string(enum) | GENERAL / READING / DEFAULT |
| spaceLabel | string | space_name 그대로 |
| selectedSchedule | DaySchedule | A02에서는 state=OPEN만 |
| availability.status | string(enum) | A02에서는 AVAILABLE만 |
| availability.basis | string(enum) | WEEKLY_SCHEDULE / ACCESS_TYPE(ALWAYS_OPEN) |
| availability.requiresReservation | boolean | accessType=RESERVATION이면 true. 실제 예약 확인이 아님 |
| availability.unavailableIntervals | Gap[] | A02에서는 항상 [] |

```json
{
  "data": {
    "criteria": { "keyword": null, "district": "수지구", "facilityTypeIds": [1], "dayOfWeek": 1, "startTime": "19:00", "endTime": "21:00" },
    "items": [
      {
        "facilityId": "101", "typeId": 1, "typeName": "도서관", "district": "수지구", "accessType": "OPEN_HOURS",
        "name": "예시 공공도서관", "address": "용인시 수지구 예시 주소", "imageUrl": "/images/default/library.png",
        "coordinates": { "latitude": 37.32, "longitude": 127.1 },
        "distanceMeters": null,
        "availableSpaces": [
          {
            "spaceType": "READING", "spaceLabel": "열람실",
            "selectedSchedule": { "dayOfWeek": 1, "state": "OPEN", "intervals": [{ "openTime": "09:00", "closeTime": "22:00" }] },
            "availability": { "status": "AVAILABLE", "basis": "WEEKLY_SCHEDULE", "requiresReservation": false, "unavailableIntervals": [] }
          }
        ]
      },
      {
        "facilityId": "202", "typeId": 2, "typeName": "생활체육시설", "district": "수지구", "accessType": "ALWAYS_OPEN",
        "name": "예시 공원 코트", "address": "용인시 수지구 예시 주소 2", "imageUrl": "/images/default/sports.png",
        "coordinates": { "latitude": 37.33, "longitude": 127.09 },
        "distanceMeters": null,
        "availableSpaces": [
          {
            "spaceType": "DEFAULT", "spaceLabel": "시설 전체",
            "selectedSchedule": { "dayOfWeek": 1, "state": "OPEN", "intervals": [{ "openTime": "00:00", "closeTime": "24:00" }] },
            "availability": { "status": "AVAILABLE", "basis": "ACCESS_TYPE", "requiresReservation": false, "unavailableIntervals": [] }
          }
        ]
      }
    ],
    "totalCount": 2,
    "matchedSpaceCount": 2,
    "unknownSpaceCount": 0
  },
  "meta": {
    "requestId": "req-demo-001",
    "generatedAt": "2026-11-01T22:45:00+09:00",
    "warnings": [
      { "code": "WEEKLY_SCHEDULE_ONLY", "message": "정규 운영시간 기준입니다. 공휴일·임시휴관과 예약 여부는 별도 확인하세요.", "facilityId": null }
    ]
  }
}
```

#### 동작 규칙

- 공간 중 하나라도 희망 구간 전체를 덮으면 시설을 한 번 반환한다(공통 정정 요약의 판정 규칙).
- 행이 없거나 UNKNOWN인 공간은 제외하고 unknownSpaceCount에 센다. CLOSED뿐인 공간은 제외하고 세지 않는다.
- ALWAYS_OPEN은 운영시간 행이 없어도 항상 포함한다.
- RESERVATION은 같은 시간 필터로 포함하고 requiresReservation=true. FE는 ‘운영시간 충족 · 예약 필요’로 표시한다.
- 시설 출처(공공도서관·작은도서관)로 거르지 않는다. 작은도서관 180곳 이상이 결과에 포함된다 [미정: 노출 정책].
- 결과 0건은 200, `items: []`. unknownSpaceCount>0이면 운영정보 부족을 함께 안내한다.

#### 상태코드

| HTTP | code | 처리 |
|---|---|---|
| 200 | - | 검색 성공·0건 |
| 400 | INVALID_REQUEST | 요일·구·typeId 오류, 필수 파라미터 누락 |
| 400 | INVALID_TIME_RANGE | 시간 형식·범위 오류, 종료<=시작 |
| 503 | DATA_UNAVAILABLE | DB 연결 불가 등 |

---

## A03. 시설 상세 및 공간별 운영시간

- `GET /api/v1/facilities/{facilityId}`
- Path facilityId: 10진수 양수 문자열. Query dayOfWeek(0~6), startTime/endTime(HH:mm) 필수.
- 전체 공간을 반환한다. 시간 조건에 맞지 않아도 시설이 존재하면 200. 인증: 추가 논의 필요.

```
GET /api/v1/facilities/101?dayOfWeek=1&startTime=19%3A00&endTime=21%3A00
Accept: application/json
```

#### 정정 내용 [B03]

| # | 항목 | v0.2 | 정정 |
|---|---|---|---|
| 1 | dataUpdatedAt | data_updated_at, 의미 미확정 | facility.source_updated_at(원천 기준일). date 형식, 없으면 null(키 유지). loaded_at은 쓰지 않음 |
| 2 | amenities | 저장자료 없어 null | 항상 문자열 배열, 태그가 없으면 []. `[]`는 확인된 태그가 없다는 뜻이며 ‘없음 확인’을 뜻하지 않는다 |
| 3 | weeklyHours | openTime/closeTime | 요일순 7개 DaySchedule. 행이 없는 요일은 UNKNOWN(intervals=[]), 고정 휴관은 CLOSED(intervals=[]) |
| 4 | availability | 판정 방식 부족 | AVAILABLE / UNAVAILABLE / UNKNOWN 모두 내려줌(아래 표) |
| 5 | 경고 | AMENITIES_NOT_AVAILABLE 포함 | 사용 안 함. WEEKLY_SCHEDULE_ONLY만 |
| 6 | spaceLabel | 단일 공간 | space_name 그대로 |
| 7 | ALWAYS_OPEN | 가상 DEFAULT | DEFAULT 공간 하나, 7일 OPEN, intervals=[{00:00, 24:00}], AVAILABLE, basis=ACCESS_TYPE |
| 8 | 오류 검증 순서 | 없음 | facilityId 형식 → dayOfWeek → 시간 형식·범위 → DB 조회(시설 없음 404) |

#### 응답

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| data.criteria | object | 예 | 아니오 | dayOfWeek / startTime / endTime |
| data.facility | FacilityDetail | 예 | 아니오 | FacilityBase + 아래 필드 |
| FacilityDetail.phone | string | 예 | 예 | 전화번호 |
| FacilityDetail.closedDayNote | string | 예 | 예 | 휴관일 원문(표시용). 격주 휴관은 여기에만 있고 요일을 CLOSED로 바꾸지 않음 |
| FacilityDetail.dataUpdatedAt | string(date) | 예 | 예 | source_updated_at |
| FacilityDetail.amenities | string[] | 예 | 아니오 | 편의시설 문구, 없으면 [] |
| FacilityDetail.spaces | SpaceDetail[] | 예 | 아니오 | 전체 공간. GENERAL → READING → DEFAULT |
| SpaceDetail.* | SpaceAvailability | 예 | - | spaceType, spaceLabel, selectedSchedule, availability |
| SpaceDetail.weeklyHours | DaySchedule[] | 예 | 아니오 | 요일순 7개 |

#### availability 판정 (선택 요일 기준)

| selectedSchedule.state | availability.status | unavailableIntervals |
|---|---|---|
| OPEN, 구간이 희망 구간을 빈틈없이 덮음 | AVAILABLE | [] |
| OPEN, 빈 구간 있음(일찍 종료, 휴게시간 등) | UNAVAILABLE | 덮이지 않는 구간 목록 |
| CLOSED | UNAVAILABLE | 요청 전체 구간 한 개 |
| UNKNOWN | UNKNOWN | null |

- basis는 WEEKLY_SCHEDULE(ALWAYS_OPEN만 ACCESS_TYPE). accessType=RESERVATION이면 모든 공간에서 requiresReservation=true.

#### amenities 문구 규칙

- READING 공간이 있으면 ‘열람실’을 맨 앞에 넣는다.
- 그다음 facility_amenity를 코드 순서(PARKING, RENTAL_ROOM)로 넣는다. detail이 있으면 그대로(예: 주차 120면), 없으면 기본 문구(주차 가능, 대관 가능).

```json
{
  "data": {
    "criteria": { "dayOfWeek": 1, "startTime": "19:00", "endTime": "21:00" },
    "facility": {
      "facilityId": "101", "typeId": 1, "typeName": "도서관", "district": "수지구", "accessType": "OPEN_HOURS",
      "name": "예시 공공도서관", "address": "용인시 수지구 예시 주소", "imageUrl": "/images/default/library.png",
      "coordinates": { "latitude": 37.32, "longitude": 127.1 },
      "phone": null,
      "closedDayNote": "예시: 매월 둘째·넷째 월요일 및 법정공휴일 휴관",
      "dataUpdatedAt": "2026-10-31",
      "amenities": ["열람실", "대관 1실 (세미나실)"],
      "spaces": [
        {
          "spaceType": "GENERAL", "spaceLabel": "종합자료실",
          "selectedSchedule": { "dayOfWeek": 1, "state": "OPEN", "intervals": [{ "openTime": "09:00", "closeTime": "18:00" }] },
          "availability": { "status": "UNAVAILABLE", "basis": "WEEKLY_SCHEDULE", "requiresReservation": false, "unavailableIntervals": [{ "startTime": "19:00", "endTime": "21:00" }] },
          "weeklyHours": [
            { "dayOfWeek": 0, "state": "CLOSED", "intervals": [] },
            { "dayOfWeek": 1, "state": "OPEN", "intervals": [{ "openTime": "09:00", "closeTime": "18:00" }] },
            { "dayOfWeek": 2, "state": "OPEN", "intervals": [{ "openTime": "09:00", "closeTime": "18:00" }] },
            { "dayOfWeek": 3, "state": "OPEN", "intervals": [{ "openTime": "09:00", "closeTime": "18:00" }] },
            { "dayOfWeek": 4, "state": "OPEN", "intervals": [{ "openTime": "09:00", "closeTime": "18:00" }] },
            { "dayOfWeek": 5, "state": "OPEN", "intervals": [{ "openTime": "09:00", "closeTime": "13:00" }] },
            { "dayOfWeek": 6, "state": "UNKNOWN", "intervals": [] }
          ]
        },
        {
          "spaceType": "READING", "spaceLabel": "열람실",
          "selectedSchedule": { "dayOfWeek": 1, "state": "OPEN", "intervals": [{ "openTime": "09:00", "closeTime": "22:00" }] },
          "availability": { "status": "AVAILABLE", "basis": "WEEKLY_SCHEDULE", "requiresReservation": false, "unavailableIntervals": [] },
          "weeklyHours": [
            { "dayOfWeek": 0, "state": "CLOSED", "intervals": [] },
            { "dayOfWeek": 1, "state": "OPEN", "intervals": [{ "openTime": "09:00", "closeTime": "22:00" }] },
            { "dayOfWeek": 2, "state": "OPEN", "intervals": [{ "openTime": "09:00", "closeTime": "22:00" }] },
            { "dayOfWeek": 3, "state": "OPEN", "intervals": [{ "openTime": "09:00", "closeTime": "22:00" }] },
            { "dayOfWeek": 4, "state": "OPEN", "intervals": [{ "openTime": "09:00", "closeTime": "22:00" }] },
            { "dayOfWeek": 5, "state": "OPEN", "intervals": [{ "openTime": "09:00", "closeTime": "18:00" }] },
            { "dayOfWeek": 6, "state": "UNKNOWN", "intervals": [] }
          ]
        }
      ]
    }
  },
  "meta": {
    "requestId": "req-demo-001",
    "generatedAt": "2026-11-01T22:45:00+09:00",
    "warnings": [
      { "code": "WEEKLY_SCHEDULE_ONLY", "message": "정규 운영시간 기준입니다. 공휴일·임시휴관과 예약 여부는 별도 확인하세요.", "facilityId": null }
    ]
  }
}
```

미니맵은 FacilityBase.coordinates로 그린다. 교통 배지는 A05를 별도로 호출해 실패가 시설 상세를 지우지 않게 한다.

#### 상태코드

| HTTP | code | 처리 |
|---|---|---|
| 200 | - | AVAILABLE / UNAVAILABLE / UNKNOWN 모두 포함 |
| 400 | INVALID_REQUEST | facilityId 형식 오류·Long 범위 초과, dayOfWeek 범위 오류, 필수 파라미터 누락 |
| 400 | INVALID_TIME_RANGE | 시간 형식·범위 오류, 종료<=시작 |
| 404 | FACILITY_NOT_FOUND | 유효한 ID의 시설 없음 |
| 503 | DATA_UNAVAILABLE | DB 연결 불가 등 |

---

## A04. 공간별 불일치 분석 및 AI 개선안

- `POST /api/v1/improvement-analyses`
- Headers: `Content-Type: application/json`, `Accept: application/json`. 인증: 추가 논의 필요.
- 선택 헤더 `X-Client-Id`: 브라우저별 UUID(`crypto.randomUUID()`를 localStorage에 저장해 재사용). AI 호출 한도의 개인 키로 쓴다. 없거나 UUID 형식이 아니면 무시하고 IP를 개인 키로 쓴다. 응답·DB·로그에 남기지 않는다. [구현]
- 요청 Body: facilityId(최상위)와 criteria 객체 모두 필수. criteria 내부는 Criteria(A02 Query와 같은 여섯 필드). [구현]
- 동기 처리 후 200. 캐시 행을 새로 만들어도 201로 구분하지 않는다.
- FE는 시설 상세 패널(FacilityDetailDrawer → AiRecommendation)에서 버튼을 눌렀을 때만 호출한다.

```json
{ "facilityId": "23", "criteria": { "keyword": null, "district": "수지구", "facilityTypeIds": [1], "dayOfWeek": 1, "startTime": "19:00", "endTime": "21:00" } }
```

#### 요청 Body 필드

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| facilityId | string | 예 | 아니오 | 분석할 시설. 10진수 양수 문자열(BIGINT). 누락·null이면 400 (details[].field `facilityId`, reason `REQUIRED`) [구현] |
| criteria | Criteria | 예 | 아니오 | A02 Query와 같은 여섯 필드 |

#### 분석 대상 [구현]

facilityId 시설의 공간 중 희망 시간에 UNAVAILABLE인 공간을 찾는다(A02 결과를 입력받지 않음).

- criteria 여섯 필드는 모두 검증하고(없는 district·typeId 등은 400) 정규화한 값을 data.criteria로 돌려준다. 후보 필터에는 dayOfWeek·startTime·endTime만 쓰고 keyword·district·facilityTypeIds는 쓰지 않는다.
- 검증 순서: facilityId 누락·형식 → criteria → 시설 조회(없으면 404 FACILITY_NOT_FOUND, A03과 같음).
- OPEN_HOURS: 공간별 분석.
- RESERVATION: 운영시간 불일치만 분석하고 예약 가능성은 판단하지 않는다. 제안에 ‘예약’을 명시하고 limitations에 1개 추가.
- ALWAYS_OPEN: 분석 대상이 아니다. 200, `candidateCount: 0`, `items: []`.
- UNKNOWN: 제외하고 unknownSpaceCount로 알리며 UNKNOWN_HOURS_EXCLUDED 경고.
- items는 공간별 배열이다. 한 시설에 대상 공간이 여러 개면 items도 여러 개이고 `spaceType`으로 구별한다. 순서는 GENERAL → READING → DEFAULT.
  - FE 안내: 도서관은 종합자료실·열람실 2개가 올 수 있다. 시설 하나의 공간은 최대 3개다.
- 한 요청의 새 생성 한도(설정값 10건, 캐시 HIT 제외)는 코드에 남아 있지만, 시설 하나의 공간이 최대 3개라 현재 구조에서는 걸리지 않는다. 따라서 proposalReason "이번 요청의 생성 한도 초과"는 현재 구조에서는 발생하지 않음.
- LLM 호출당 20초, 요청 전체 30초 제한. 전체 시간을 넘으면 남은 항목은 UNAVAILABLE. 항목당 LLM 호출은 1번이며 재시도하지 않는다.

#### AI 호출 한도 [구현]

OpenAI 크레딧 소진을 막기 위한 서버 메모리 기반 한도다(PR #16).

| 구분 | 키 | 3분 슬라이딩 윈도우 한도(기본값) |
|---|---|---|
| 개인 | `X-Client-Id`(UUID). 없거나 형식이 틀리면 IP | 10건 |
| IP | 요청자 IP. nginx(루프백) 뒤에서는 `X-Real-IP`만 신뢰하고 `X-Forwarded-For`는 읽지 않음. IPv6는 /64 단위 | 150건 |
| 서버 전체 | - | 150건 |

- 세는 대상은 OpenAI를 **실제로 호출한 횟수**다. 캐시 HIT는 세지 않고, 호출이 실패해도 1건 소모한다. 세 한도를 모두 통과할 때만 소모한다.
- 한도를 넘어도 응답은 **200**이다. 한도에 걸린 항목만 `proposalStatus: "UNAVAILABLE"`, `cache.status: "UNAVAILABLE"`이고 proposalReason은 "AI 호출 한도를 초과했습니다. N초 후 다시 시도해 주세요."이다. 같은 요청의 HIT 항목은 정상 반환한다.
- 이때 meta.warnings에 `RATE_LIMITED`(facilityId 포함)를 넣고, 응답 헤더 `Retry-After`(초)에 다시 시도할 수 있기까지 남은 시간을 준다. 한도에 걸린 항목이 없으면 `Retry-After`를 보내지 않는다.
- 값은 설정(`app.llm.rate-limit.*`, 환경 변수 `APP_LLM_RATE_LIMIT_ENABLED`·`_PER_CLIENT`·`_PER_IP`·`_GLOBAL`)으로 바꿀 수 있다. 서버를 재시작하면 기록이 초기화된다.
- 07 §3.5의 429 RATE_LIMIT_EXCEEDED와 달리 A04는 부분 응답을 유지하려고 429를 쓰지 않는다.

#### 캐시 처리 순서 [B03 + 구현]

1. 현재 운영시간으로 불일치 구간을 먼저 계산한다. 현재 AVAILABLE인 대상에는 과거 캐시를 보여주지 않는다.
2. `(scope_key, analysis_type, params_key)`로 ai_analysis를 조회한다. 예: scope_key `FAC:101`, analysis_type `IMPROVEMENT`, params_key `S:GENERAL|D:1|T:1140-1260`(공간|요일|희망 시간 분 구간).
3. 행이 있고 source_hash와 prompt_version이 현재 값과 모두 같으며 payload 재검증을 통과할 때만 HIT로 반환하고 LLM을 다시 호출하지 않는다. source_hash는 LLM 입력 근거(운영시간, 정류장 수 등)의 SHA-256이다.
4. 행이 없거나 hash·version이 다르면 검증된 사실(운영시간, transport가 AVAILABLE일 때의 정류장 수)로 LLM에 요청한다.
5. 응답은 JSON으로만 받아 서버에서 검증한다: alternatives가 문자열 배열인지, proposedClosingTime이 현재 구간에 맞는지, 원천에 없는 숫자가 없는지(‘N개·N곳’은 정류장 수와 같아야 함), 정류장 수가 입력에 없는데 정류장을 언급하지 않는지, 배차·막차·첫차·운행 간격·도착 시간·분 간격 표현이 없는지. 검증 실패 결과는 저장하지 않는다.
6. 검증된 결과를 ai_analysis.payload(JSONB)에 저장한다. 행이 없으면 INSERT, hash·version이 달라 재생성하면 같은 행을 UPDATE하고 CACHE_INVALIDATED 경고를 붙인다. 동시 동일 요청은 키별 락과 UNIQUE 충돌 후 재조회로 처리한다.
7. LLM 실패 시 운영시간·불일치 구간은 유지한다. 캐시 쓰기만 실패하면 생성 결과는 반환하고 CACHE_WRITE_FAILED, 조회 실패는 CACHE_READ_FAILED 후 생성을 시도한다.

selectedSchedule·availability·transport처럼 매번 계산되는 값은 저장하지 않는다.

#### 응답 data 필드

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| data.criteria | Criteria | 예 | 아니오 | 정규화한 여섯 조건 |
| data.candidateCount | integer | 예 | 아니오 | items.length |
| data.facilityCount | integer | 예 | 아니오 | items 내 서로 다른 facilityId 수. 시설 하나만 분석하므로 0 또는 1 [구현] |
| data.unknownSpaceCount | integer | 예 | 아니오 | 시간 미확인으로 제외한 공간 수 |
| data.items | ImprovementItem[] | 예 | 아니오 | 공간별 제안 |
| data.disclaimer | string | 예 | 아니오 | AI 의견은 참고용이라는 안내 |

#### ImprovementItem

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| facility | FacilityBase | 예 | 아니오 | 대상 시설 |
| spaceType | string(enum) | 예 | 아니오 | 대상 공간 |
| spaceLabel | string | 예 | 아니오 | space_name |
| selectedSchedule | DaySchedule | 예 | 아니오 | 선택 요일 운영시간(intervals 배열) |
| availability | Availability | 예 | 아니오 | UNAVAILABLE. 미운영 구간은 unavailableIntervals |
| currentClosingTime | string(time) | 예 | 예 | 선택 요일 마지막 구간의 closeTime. CLOSED면 null |
| proposedClosingTime | string(time) | 예 | 예 | 제안 종료. 종료 연장으로 해결 못하면 null |
| proposalStatus | string(enum) | 예 | 아니오 | GENERATED / UNAVAILABLE |
| proposalReason | string | 예 | 예 | 서버가 사실로 만든 고정 문장. 실패 시 "AI 제안을 생성하지 못했습니다." ("이번 요청의 생성 한도 초과"는 코드에 남아 있으나 현재 구조에서는 발생하지 않음) [구현] |
| alternatives | string[] | 예 | 아니오 | 운영 대안. 실패면 [] |
| integratedSuggestion | IntegratedSuggestion | 예 | 아니오 | 운영·교통 종합 제안 |
| transport | TransportAccessibility | 예 | 아니오 | A05와 같은 타입 |
| weakTimeAnalysis | WeakTimeAnalysis | 예 | 아니오 | 현재 NOT_SUPPORTED |
| suggestionId | string | 예 | 예 | ai_analysis.analysis_id. 저장된 행이 없으면 null |
| cache | CacheInfo | 예 | 아니오 | 이 요청의 캐시 처리 결과 |
| limitations | string[] | 예 | 아니오 | 서버 고정 문장. 예약제면 1개 추가. transport AVAILABLE이면 "정류장 개수 외 시간대별 운행자료가 없습니다." |

#### CacheInfo

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| status | string(enum) | 예 | 아니오 | HIT / CREATED / NOT_STORED / UNAVAILABLE |
| createdAt | string(date-time) | 예 | 예 | HIT/CREATED는 캐시 행의 updated_at(현재 내용이 만들어진 시각, +09:00). 나머지는 null [구현] |

- HIT: 유효 캐시 재사용. suggestionId와 createdAt non-null.
- CREATED: 이번 요청에서 생성·저장 성공(재생성 포함).
- NOT_STORED: 생성 결과는 있으나 저장 실패. suggestionId/createdAt null, CACHE_WRITE_FAILED.
- UNAVAILABLE: 유효 캐시/생성 결과 없음. proposalStatus=UNAVAILABLE.

> B03 초안은 createdAt을 created_at으로 적었으나, 재생성 행은 created_at이 최초 시각으로 남으므로 구현은 updated_at을 쓴다.

#### IntegratedSuggestion

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| status | string(enum) | 예 | 아니오 | COMPLETE / PARTIAL / UNAVAILABLE |
| text | string | 예 | 예 | COMPLETE/PARTIAL은 문장, UNAVAILABLE은 null |

- COMPLETE는 운영시간과 확보된 교통 근거를 함께 반영했다는 뜻이다. bus_stop_count만 있다면 ‘500m 내 정류장 N개’라는 공간 정보까지만 쓸 수 있고, 실제 배차·귀가 가능을 검증했다는 뜻은 아니다. 교통 근거가 없으면 PARTIAL이다.
- 판정 규칙: transport.status=AVAILABLE이고 text에 정류장 수가 실제로 들어 있으면 COMPLETE. 생성은 성공했지만 그 외(AVAILABLE인데 정류장 미언급, NO_DATA, NOT_ENABLED)는 PARTIAL. 생성 실패는 UNAVAILABLE. status는 저장하지 않고 응답 시 정한다.
- 정류장 수는 source_hash에 포함되므로 값이 바뀌면 캐시가 재생성된다. 근거가 바뀐 과거 문장을 COMPLETE로 재사용하지 않는다(09 T21).
- **[구현 상태]** 현재 코드는 생성 성공 시 항상 PARTIAL이다. 위 규칙으로 수정 예정(fix/integrated-suggestion-complete).

#### WeakTimeAnalysis

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| status | string(enum) | 예 | 아니오 | 현재 NOT_SUPPORTED |
| periods | WeakTimePeriod[] | 예 | 아니오 | 현재 [] |
| WeakTimePeriod.dayOfWeek | integer | 예 | 아니오 | 0~6 |
| WeakTimePeriod.startTime / endTime | string(time) | 예 | 아니오 | 취약 구간 |
| WeakTimePeriod.cause / evidence | string | 예 | 아니오 | 실제 데이터 기반, 임의 수치 금지 |

P1 취약 시간 기능은 운행 근거 데이터가 없어 미지원이다. ‘21시 이후 배차간격 24분’ 같은 값은 현재 데이터로 산출할 수 없다.

#### 응답 예시 — 정류장 수 반영, 캐시 HIT

```json
{
  "data": {
    "criteria": { "keyword": null, "district": "수지구", "facilityTypeIds": [1], "dayOfWeek": 1, "startTime": "19:00", "endTime": "21:00" },
    "candidateCount": 1,
    "facilityCount": 1,
    "unknownSpaceCount": 0,
    "items": [
      {
        "facility": {
          "facilityId": "101", "typeId": 1, "typeName": "도서관", "district": "수지구", "accessType": "OPEN_HOURS",
          "name": "예시 공공도서관", "address": "용인시 수지구 예시 주소", "imageUrl": "/images/default/library.png",
          "coordinates": { "latitude": 37.32, "longitude": 127.1 }
        },
        "spaceType": "GENERAL",
        "spaceLabel": "종합자료실",
        "selectedSchedule": { "dayOfWeek": 1, "state": "OPEN", "intervals": [{ "openTime": "09:00", "closeTime": "18:00" }] },
        "availability": { "status": "UNAVAILABLE", "basis": "WEEKLY_SCHEDULE", "requiresReservation": false, "unavailableIntervals": [{ "startTime": "19:00", "endTime": "21:00" }] },
        "currentClosingTime": "18:00",
        "proposedClosingTime": "21:00",
        "proposalStatus": "GENERATED",
        "proposalReason": "종합자료실의 운영 종료(18:00)가 희망 이용 시간(19:00–21:00)보다 빠릅니다.",
        "alternatives": ["화요일 종합자료실 운영 종료 시간을 21:00까지 연장하는 방안을 검토할 수 있습니다."],
        "integratedSuggestion": {
          "status": "COMPLETE",
          "text": "종합자료실의 화요일 운영 종료를 21:00까지 연장하는 방안을 검토할 수 있습니다. 시설 500m 내 정류장 4개가 있으며, 실제 운행은 별도 확인이 필요합니다."
        },
        "transport": {
          "status": "AVAILABLE", "radiusMeters": 500, "stopCount": 4, "grade": null,
          "notes": ["등급 기준은 추가 논의가 필요합니다.", "정류장 수는 시간대별 운행 가능성을 의미하지 않습니다."]
        },
        "weakTimeAnalysis": { "status": "NOT_SUPPORTED", "periods": [] },
        "suggestionId": "301",
        "cache": { "status": "HIT", "createdAt": "2026-11-01T21:00:00+09:00" },
        "limitations": [
          "주간 정규시간 기준이며 공휴일·주차별 휴관을 판정하지 않습니다.",
          "정류장 개수 외 시간대별 운행자료가 없습니다.",
          "운영 인력·예산·시설 규정을 검토한 확정 정책이 아닙니다."
        ]
      }
    ],
    "disclaimer": "AI 제안은 참고 의견이며 공식 운영시간이나 확정된 정책이 아닙니다. 실제 이용 전 공식 홈페이지 또는 전화로 확인하세요."
  },
  "meta": {
    "requestId": "req-demo-001",
    "generatedAt": "2026-11-01T22:45:00+09:00",
    "warnings": [
      { "code": "WEEKLY_SCHEDULE_ONLY", "message": "정규 운영시간 기준입니다. 공휴일·임시휴관과 예약 여부는 별도 확인하세요.", "facilityId": null },
      { "code": "GRADE_RULE_NOT_DEFINED", "message": "정류장 수는 제공하지만 등급 기준은 미정입니다.", "facilityId": "101" }
    ]
  }
}
```

#### 제안 대상 없음 — 200

후보가 없으면 LLM 호출 없이 `candidateCount: 0`, `items: []`로 반환한다. unknownSpaceCount>0이면 일부 미판정 상태임을 함께 안내한다.

#### LLM 실패 시 바뀌는 item 필드

```json
{
  "proposedClosingTime": null,
  "proposalStatus": "UNAVAILABLE",
  "proposalReason": "AI 제안을 생성하지 못했습니다.",
  "alternatives": [],
  "integratedSuggestion": { "status": "UNAVAILABLE", "text": null },
  "suggestionId": null,
  "cache": { "status": "UNAVAILABLE", "createdAt": null }
}
```

facility/spaceType/selectedSchedule/availability/transport는 유지하고 meta.warnings에 LLM_UNAVAILABLE을 넣는다(시설당 1건으로 합침). 전체 응답은 200이다. OPENAI_API_KEY가 없어도 서버는 기동하며 같은 경로로 처리한다.

#### 경고 코드 (A04에서 나올 수 있는 것) [구현]

WEEKLY_SCHEDULE_ONLY(항상), UNKNOWN_HOURS_EXCLUDED, LLM_UNAVAILABLE, RATE_LIMITED(AI 호출 한도 초과 항목, 시설당 1건), CACHE_READ_FAILED, CACHE_WRITE_FAILED, CACHE_INVALIDATED, TRANSPORT_DATA_UNAVAILABLE(transport NO_DATA, 시설당 1건), GRADE_RULE_NOT_DEFINED(transport AVAILABLE, 시설당 1건). RATE_LIMITED를 뺀 나머지는 07 §3.4 목록 안의 코드다. RATE_LIMITED는 PR #16에서 추가했고 07 반영은 후속 작업이다.

#### 상태코드

| HTTP | code | 처리 |
|---|---|---|
| 200 | - | 캐시 HIT / 새 생성 / 후보 없음 / LLM·캐시 일부 실패 / AI 호출 한도 초과(해당 항목 UNAVAILABLE, `Retry-After` 헤더) |
| 400 | INVALID_REQUEST / INVALID_TIME_RANGE | JSON·조건 오류. details[].field는 `criteria.dayOfWeek`처럼 `criteria.` 접두사 [구현] |
| 400 | INVALID_REQUEST | facilityId 누락·null은 reason REQUIRED, 형식 오류(빈 문자열·숫자 아님·0 이하)는 INVALID_FORMAT, Long 범위 초과는 OUT_OF_RANGE. details[].field는 `facilityId`(접두사 없음) [구현] |
| 404 | FACILITY_NOT_FOUND | facilityId 시설이 없음 (A03과 같음) [구현] |
| 415 | UNSUPPORTED_MEDIA_TYPE | JSON 본문 필요 |
| 503 | DATA_UNAVAILABLE | 시설 DB 조회 불가 |

FE는 같은 조건의 진행 중 요청을 공유하고, 조건 변경 후 이전 응답이 새 화면을 덮어쓰지 않게 한다. 첫 생성은 수 초가 걸리며 최대 30초이므로 FE 타임아웃은 40초 정도로 잡는다.

---

## A05. 시설 교통 접근성 조회

- `GET /api/v1/facilities/{facilityId}/transport-accessibility`
- Path facilityId: string 필수. Query / Body 없음. 인증: 추가 논의 필요.
- P1 조건부 API. ETL이 적재한 facility.bus_stop_count(시설 반경 500m 정류장 수)를 조회한다. 조회 중 외부 API를 호출하지 않는다.

```
GET /api/v1/facilities/101/transport-accessibility
Accept: application/json
```

#### 응답

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| data.facilityId | string | 예 | 아니오 | 대상 시설 |
| data.accessibility | TransportAccessibility | 예 | 아니오 | 아래 공통 타입 |

#### TransportAccessibility — A04에서도 재사용

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| status | string(enum) | 예 | 아니오 | AVAILABLE / NO_DATA / NOT_ENABLED |
| radiusMeters | integer | 예 | 아니오 | 500 |
| stopCount | integer | 예 | 예 | bus_stop_count, 0 이상. null은 미확인, 0은 확인 결과 0개 |
| grade | TransportGrade | 예 | 예 | 산식 미정이라 현재 null |
| notes | string[] | 예 | 아니오 | 자료·판정의 한계 |
| TransportGrade.code / label / ruleVersion | string | 예 | 아니오 | 등급 산식 합의 후 |

- 교통 자료의 기준일은 facility.bus_counted_at(정류장 수 계산일)이다. source_updated_at을 교통 최신성 날짜로 재사용하지 않는다. 응답에 노출할지는 [미정]. [B03]
- 정류장 자료는 용인시 정류장만 포함한다. 시 경계 근처 시설은 실제보다 적게 나올 수 있다. 이름에 "(미정차)"가 붙은 정류장(144개)은 반경 500m 집계에서 제외한다. [구현]
- 등급은 합의된 산식이 있어야 제공한다. 정류장 수가 있다고 자동으로 ‘양호’라고 정하지 않는다.
- NOT_ENABLED는 A05 응답에서는 나오지 않고, A04에서 교통 연결 스위치(app.analysis.transport-link.enabled)가 꺼졌거나 변환 중 예외가 났을 때만 나온다. [구현]

```json
{
  "data": {
    "facilityId": "101",
    "accessibility": {
      "status": "AVAILABLE", "radiusMeters": 500, "stopCount": 4, "grade": null,
      "notes": ["등급 기준은 추가 논의가 필요합니다.", "정류장 수는 시간대별 운행 가능성을 의미하지 않습니다."]
    }
  },
  "meta": {
    "requestId": "req-demo-001",
    "generatedAt": "2026-11-01T22:45:00+09:00",
    "warnings": [{ "code": "GRADE_RULE_NOT_DEFINED", "message": "정류장 수는 제공하지만 등급 기준은 미정입니다.", "facilityId": "101" }]
  }
}
```

```json
{
  "data": {
    "facilityId": "101",
    "accessibility": {
      "status": "NO_DATA", "radiusMeters": 500, "stopCount": null, "grade": null,
      "notes": ["500m 정류장 집계값이 적재되지 않았습니다."]
    }
  },
  "meta": {
    "requestId": "req-demo-001",
    "generatedAt": "2026-11-01T22:45:00+09:00",
    "warnings": [{ "code": "TRANSPORT_DATA_UNAVAILABLE", "message": "교통 정보를 확인할 수 없습니다.", "facilityId": "101" }]
  }
}
```

#### 상태코드

| HTTP | code | 처리 |
|---|---|---|
| 200 | - | AVAILABLE(0 포함) / NO_DATA |
| 400 | INVALID_REQUEST | facilityId 형식 오류 |
| 404 | FACILITY_NOT_FOUND | 시설 없음 |
| 503 | DATA_UNAVAILABLE | 시설 DB 조회 실패 |

v0.2의 503 FEATURE_NOT_ENABLED는 현재 구현에 on/off가 없어 쓰지 않는다.

---

## 결정이 필요한 항목

| 항목 | 내용 |
|---|---|
| 구간 타입 이름 | 코드 Gap / 07 Interval |
| A01 chip_group | 04와 10의 칩 정의가 다름. A01에 포함할지 |
| imageUrl | 기준 URL과 이미지 파일 위치(FE public 또는 BE 서빙) |
| 작은도서관 노출 | 도서관 유형 206곳 중 작은도서관 185곳. A04 후보 대부분을 차지. 별도 유형 분리 등 |
| 일요일 = 토요일 | 도서관 표준데이터에 일요일 컬럼이 없어 ETL이 토요일 시간을 채움(19곳, note 표시) |
| ai_analysis 다른 종류 | ACCESSIBILITY / VULNERABLE_AREA / REGION_INSIGHT에 대응하는 API 없음 |
| RESERVATION 데이터 | ETL이 예약제를 구분하지 못해 실데이터는 0건(로컬 샘플만 있음) |
| 비UTF-8 쿼리스트링 | 현재 500. 400 처리 여부 |
| 호출 제한 | 07은 "채택 시에만 429". 정책 확정 시 반영 |
| 문서 반영 | 07·08·09·10 본문을 이 정정에 맞게 수정 |
