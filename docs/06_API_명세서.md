# API 명세서

v0.2 · 기능명세서 기준으로 설계하고 데이터 테이블 정합성을 반영했습니다. 아래 표의 API명을 열면 요청 파라미터·필드 타입·JSON 예시·상태코드를 확인할 수 있습니다.

- A01~A05 순서로 정리했습니다. 표의 빠른 필터로 기능 그룹·구현 범위·문서 상태를 선택할 수 있습니다.
- ‘검토 중’은 API 계약 합의 전이라는 뜻입니다. 구현·테스트 완료를 의미하지 않습니다.
- 담당자는 팀 배정 후 입력합니다. 인증과 미정 정책은 공통 문서를 참고합니다.

## API 목록

| API명 | API ID | Method | Endpoint | 기능 그룹 | 구현 범위 | 문서 상태 |
|---|---|---|---|---|---|---|
| 검색 선택지 조회 | A01 | GET | /api/v1/facility-search-options | 검색 선택지 | 지원 제안 | 검토 중 |
| 시간대별 시설 검색 | A02 | GET | /api/v1/facilities | 시설 검색 | P0 핵심 | 검토 중 |
| 시설 상세 및 공간별 운영시간 | A03 | GET | /api/v1/facilities/{facilityId} | 시설 상세 | P0 핵심 | 검토 중 |
| 공간별 불일치 분석 및 AI 개선안 | A04 | POST | /api/v1/improvement-analyses | AI 분석 | P0 핵심 | 검토 중 |
| 시설 교통 접근성 조회 | A05 | GET | /api/v1/facilities/{facilityId}/transport-accessibility | 교통 접근성 | P1 조건부 | 검토 중 |

## 공통 문서

별도 파일로 함께 저장했습니다.

- 공통 규칙·응답 타입 → `07_API_공통규칙_응답타입.md`
- DB 매핑·보완 및 논의 사항 → `08_DB_매핑_보완_논의사항.md`
- 외부 연동·요구사항 추적·검증 기준 → `09_외부연동_요구사항추적_검증기준.md`

---

## A01. 검색 선택지 조회

공통 필드·성공/오류 envelope는 「공통 규칙·응답 타입」을 따릅니다. 스키마 보완과 미정 정책은 「DB 매핑·보완 및 논의 사항」에서 확인합니다. 현재 상태는 계약 검토 중입니다.

- `GET /api/v1/facility-search-options`
- 목적: 실제 facility_type의 ID와 표시값 제공, 지역·요일·공간 코드 동기화.
- Path / Query / Body: 없음.
- 인증: 추가 논의 필요.
- 응답: 200 + 공통 envelope.

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| data.facilityTypes | FacilityTypeOption[] | 예 | 아니오 | 실제 DB에 등록된 유형 |
| FacilityTypeOption.typeId | integer | 예 | 아니오 | 유형 ID |
| FacilityTypeOption.name | string | 예 | 아니오 | 유형명 |
| FacilityTypeOption.defaultImageUrl | string(uri) | 예 | 예 | 유형 기본 이미지 |
| data.districts | string[] | 예 | 아니오 | 수지구/기흥구/처인구 |
| data.daysOfWeek | DayOption[] | 예 | 아니오 | 요일 선택지 |
| DayOption.value | integer | 예 | 아니오 | 0~6 |
| DayOption.label | string | 예 | 아니오 | 요일명 |
| data.defaultDayOfWeek | integer | 예 | 아니오 | 응답 시점 한국 요일 |
| data.timeZone | string | 예 | 아니오 | Asia/Seoul 제안 |

```json
{
  "data": {
    "facilityTypes": [
      {
        "typeId": 1,
        "name": "도서관",
        "defaultImageUrl": null
      },
      {
        "typeId": 2,
        "name": "생활체육시설",
        "defaultImageUrl": null
      }
    ],
    "districts": [
      "수지구",
      "기흥구",
      "처인구"
    ],
    "daysOfWeek": [
      {
        "value": 0,
        "label": "월"
      },
      {
        "value": 1,
        "label": "화"
      },
      {
        "value": 2,
        "label": "수"
      },
      {
        "value": 3,
        "label": "목"
      },
      {
        "value": 4,
        "label": "금"
      },
      {
        "value": 5,
        "label": "토"
      },
      {
        "value": 6,
        "label": "일"
      }
    ],
    "defaultDayOfWeek": 6,
    "timeZone": "Asia/Seoul"
  },
  "meta": {
    "requestId": "req-demo-001",
    "generatedAt": "2026-11-01T22:45:00+09:00",
    "warnings": []
  }
}
```

위 유형 ID는 설명용이다. 도서관/작은도서관 구분을 유형으로 나눌지 현재 ‘도서관’에 합칠지는 적재 정책에 따른다. 평생학습관은 기능명세서의 예시지만 현재 테이블 예시에 없으므로 지원 여부를 별도 확인한다. A01은 등록된 유형만 반환한다.

상태코드: 200 정상, 503 DATA_UNAVAILABLE(DB/코드 자료 불가), 나머지는 3.5 공통. facilityTypes=[]는 정상 빈 구성일 수 있지만 준비 완료 서비스로 볼지는 배포 점검 사항이다.

---

## A02. 시간대별 시설 검색

공통 필드·성공/오류 envelope는 「공통 규칙·응답 타입」을 따릅니다. 스키마 보완과 미정 정책은 「DB 매핑·보완 및 논의 사항」에서 확인합니다. 현재 상태는 계약 검토 중입니다.

- `GET /api/v1/facilities`
- 목적: 선택 시간 전체에 운영하는 공간을 가진 시설 조회. 시설은 중복 없이 카드/마커 하나로 제공.
- Path: 없음. Body: 없음. 인증: 추가 논의 필요.

#### Query

3.6 Criteria 여섯 필드를 사용한다. dayOfWeek/startTime/endTime 필수, 나머지는 선택이다. 거리 P1을 채택하면 아래 두 필드를 추가한다.

| 필드 | 타입 | 필수 | 검증 |
|---|---|---|---|
| latitude | number | 아니오 | -90~90, longitude와 쌍으로 전달 |
| longitude | number | 아니오 | -180~180, latitude와 쌍으로 전달 |

위치 권한 거절 시 둘 다 생략한다. 거리만 null이며 검색은 계속한다. 한쪽만 전달하거나 범위 밖이면 400 INVALID_REQUEST. 거리 계산은 BE 직선거리(m) 제안이며, 담당 계층·산식은 미정이다. 사용자 위치를 DB에 저장할 필요는 없다.

```
GET /api/v1/facilities?district=%EC%88%98%EC%A7%80%EA%B5%AC&facilityTypeIds=1&dayOfWeek=1&startTime=19%3A00&endTime=21%3A00
Accept: application/json
```

#### 응답

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| data.criteria | Criteria | 예 | 아니오 | 여섯 필드 전체를 정규화해 반환 |
| data.items | FacilitySearchItem[] | 예 | 아니오 | 시간을 만족하는 시설들, 없으면 [] |
| data.totalCount | integer | 예 | 아니오 | 시설 개수=items.length |
| data.matchedSpaceCount | integer | 예 | 아니오 | 모든 items.availableSpaces 길이의 합 |
| data.unknownSpaceCount | integer | 예 | 아니오 | 동일 키워드·지역·유형 후보에서 UNKNOWN으로 제외된 공간 수 |
| FacilitySearchItem.* | FacilityBase | 예 | 각 타입 참조 | FacilityBase 전체 필드 포함 |
| FacilitySearchItem.distanceMeters | number | 예 | 예 | 0 이상; 사용자 좌표 미전달/거리 미구현이면 null |
| FacilitySearchItem.availableSpaces | SpaceAvailability[] | 예 | 아니오 | 시간에 맞는 공간만. 시설당 1개 이상 |

```json
{
  "data": {
    "criteria": {
      "keyword": null,
      "district": "수지구",
      "facilityTypeIds": [
        1
      ],
      "dayOfWeek": 1,
      "startTime": "19:00",
      "endTime": "21:00"
    },
    "items": [
      {
        "facilityId": "101",
        "typeId": 1,
        "typeName": "도서관",
        "district": "수지구",
        "accessType": "OPEN_HOURS",
        "name": "예시 공공도서관",
        "address": "용인시 수지구 예시 주소",
        "imageUrl": null,
        "coordinates": {
          "latitude": 37.32,
          "longitude": 127.1
        },
        "distanceMeters": null,
        "availableSpaces": [
          {
            "spaceType": "READING",
            "spaceLabel": "열람실",
            "selectedSchedule": {
              "dayOfWeek": 1,
              "state": "OPEN",
              "openTime": "09:00",
              "closeTime": "22:00"
            },
            "availability": {
              "status": "AVAILABLE",
              "basis": "WEEKLY_SCHEDULE",
              "requiresReservation": false,
              "unavailableIntervals": []
            }
          }
        ]
      }
    ],
    "totalCount": 1,
    "matchedSpaceCount": 1,
    "unknownSpaceCount": 0
  },
  "meta": {
    "requestId": "req-demo-001",
    "generatedAt": "2026-11-01T22:45:00+09:00",
    "warnings": [
      {
        "code": "WEEKLY_SCHEDULE_ONLY",
        "message": "정규 운영시간 기준입니다. 공휴일·임시휴관과 예약 여부는 별도 확인하세요.",
        "facilityId": null
      }
    ]
  }
}
```

이 예시에서 GENERAL이 18시에 끝나면 availableSpaces에는 READING만 들어간다. FE는 ‘열람실 09:00–22:00’처럼 실제 매칭 공간을 표시해야 한다.

시설의 공간 중 하나 이상이 요청 전 구간을 만족하면 시설을 한 번 반환하는 방식을 제안한다. 어떤 공간이 매칭됐는지는 availableSpaces로 명확히 표시한다. 여러 공간을 합쳐 요청 구간을 덮었다고 처리하지 않는다. 모든 공간이 충족해야 시설을 포함할지 여부는 팀 합의가 필요하다. 검색 UI에는 공간 필터를 추가하지 않는다.

예약제 시설은 동일한 시간 필터로 포함하되 accessType=RESERVATION, requiresReservation=true를 유지한다. FE는 ‘이용 가능’만 단독 표시하지 않고 ‘운영시간 충족 · 예약 필요’로 표시하는 안이다. 예약제 포함 여부는 팀 합의 사항이며 자동 예약조회 기능은 없다.

#### 결과 없음 — 200

```json
{
  "data": {
    "criteria": {
      "keyword": null,
      "district": "수지구",
      "facilityTypeIds": [
        1
      ],
      "dayOfWeek": 1,
      "startTime": "19:00",
      "endTime": "21:00"
    },
    "items": [],
    "totalCount": 0,
    "matchedSpaceCount": 0,
    "unknownSpaceCount": 0
  },
  "meta": {
    "requestId": "req-demo-001",
    "generatedAt": "2026-11-01T22:45:00+09:00",
    "warnings": [
      {
        "code": "WEEKLY_SCHEDULE_ONLY",
        "message": "정규 운영시간 기준입니다. 공휴일·임시휴관과 예약 여부는 별도 확인하세요.",
        "facilityId": null
      }
    ]
  }
}
```

unknownSpaceCount>0이면 ‘열린 시설 없음’과 함께 운영정보 부족을 안내한다. 같은 시설의 한 공간은 매칭되고 다른 공간은 UNKNOWN일 수 있어 unknownSpaceCount는 시설 개수와 단순 합산하면 안 된다.

#### 상태코드

| HTTP | code / 상황 | 처리 |
|---|---|---|
| 200 | 검색 성공·0건 | 빈 결과도 정상 |
| 400 | INVALID_REQUEST | 잘못된 district/typeId/요일/좌표/필수 필드 |
| 400 | INVALID_TIME_RANGE | 유효하지 않은 시간 구간 |
| 503 | DATA_UNAVAILABLE | DB 또는 필수 시설 자료 사용 불가 |

현재 자료는 DB 적재 후 조회하므로 사용자 검색마다 원천 공공 API를 호출하지 않는다. 시설/공간 조인으로 같은 시설을 여러 행 반환하지 않게 묶는다.

---

## A03. 시설 상세 및 공간별 운영시간

공통 필드·성공/오류 envelope는 「공통 규칙·응답 타입」을 따릅니다. 스키마 보완과 미정 정책은 「DB 매핑·보완 및 논의 사항」에서 확인합니다. 현재 상태는 계약 검토 중입니다.

- `GET /api/v1/facilities/{facilityId}`
- Path facilityId: 10진수 양수 문자열, 필수.
- Query dayOfWeek: integer 0~6 필수, startTime/endTime: HH:mm 필수.
- 전체 공간을 반환한다. AI 카드에서 선택한 spaceType을 강조하는 것은 FE 상태로 처리한다.
- Body: 없음. 인증: 추가 논의 필요.

```
GET /api/v1/facilities/101?dayOfWeek=1&startTime=19%3A00&endTime=21%3A00
Accept: application/json
```

#### 응답

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| data.criteria | object | 예 | 아니오 | dayOfWeek/startTime/endTime만 포함 |
| data.criteria.dayOfWeek | integer | 예 | 아니오 | 선택 요일 |
| data.criteria.startTime | string(time) | 예 | 아니오 | 선택 시작 |
| data.criteria.endTime | string(time) | 예 | 아니오 | 선택 종료 |
| data.facility | FacilityDetail | 예 | 아니오 | FacilityBase+아래 상세 필드 |
| FacilityDetail.phone | string | 예 | 예 | 전화번호 |
| FacilityDetail.closedDayNote | string | 예 | 예 | 휴관일 원문 |
| FacilityDetail.dataUpdatedAt | string(date) | 예 | 예 | DB data_updated_at. 원천 기준일/적재일 의미 확인 필요 |
| FacilityDetail.amenities | string[] | 예 | 예 | 현재 저장자료 없어 null. []는 없음이 확인됐다는 의미 |
| FacilityDetail.spaces | SpaceDetail[] | 예 | 아니오 | 시설의 전체 공간별 정보 |
| SpaceDetail.* | SpaceAvailability | 예 | 각 타입 참조 | 공간 코드·선택시간·판정 모두 포함 |
| SpaceDetail.weeklyHours | DaySchedule[] | 예 | 아니오 | 요일순 7개, 누락 요일 UNKNOWN |

```json
{
  "data": {
    "criteria": {
      "dayOfWeek": 1,
      "startTime": "19:00",
      "endTime": "21:00"
    },
    "facility": {
      "facilityId": "101",
      "typeId": 1,
      "typeName": "도서관",
      "district": "수지구",
      "accessType": "OPEN_HOURS",
      "name": "예시 공공도서관",
      "address": "용인시 수지구 예시 주소",
      "imageUrl": null,
      "coordinates": {
        "latitude": 37.32,
        "longitude": 127.1
      },
      "phone": null,
      "closedDayNote": "예시: 매월 둘째·넷째 월요일 및 법정공휴일 휴관",
      "dataUpdatedAt": "2026-10-31",
      "amenities": null,
      "spaces": [
        {
          "spaceType": "GENERAL",
          "spaceLabel": "종합자료실",
          "selectedSchedule": {
            "dayOfWeek": 1,
            "state": "OPEN",
            "openTime": "09:00",
            "closeTime": "18:00"
          },
          "availability": {
            "status": "UNAVAILABLE",
            "basis": "WEEKLY_SCHEDULE",
            "requiresReservation": false,
            "unavailableIntervals": [
              {
                "startTime": "19:00",
                "endTime": "21:00"
              }
            ]
          },
          "weeklyHours": [
            {
              "dayOfWeek": 0,
              "state": "UNKNOWN",
              "openTime": null,
              "closeTime": null
            },
            {
              "dayOfWeek": 1,
              "state": "OPEN",
              "openTime": "09:00",
              "closeTime": "18:00"
            },
            {
              "dayOfWeek": 2,
              "state": "UNKNOWN",
              "openTime": null,
              "closeTime": null
            },
            {
              "dayOfWeek": 3,
              "state": "UNKNOWN",
              "openTime": null,
              "closeTime": null
            },
            {
              "dayOfWeek": 4,
              "state": "UNKNOWN",
              "openTime": null,
              "closeTime": null
            },
            {
              "dayOfWeek": 5,
              "state": "UNKNOWN",
              "openTime": null,
              "closeTime": null
            },
            {
              "dayOfWeek": 6,
              "state": "UNKNOWN",
              "openTime": null,
              "closeTime": null
            }
          ]
        },
        {
          "spaceType": "READING",
          "spaceLabel": "열람실",
          "selectedSchedule": {
            "dayOfWeek": 1,
            "state": "OPEN",
            "openTime": "09:00",
            "closeTime": "22:00"
          },
          "availability": {
            "status": "AVAILABLE",
            "basis": "WEEKLY_SCHEDULE",
            "requiresReservation": false,
            "unavailableIntervals": []
          },
          "weeklyHours": [
            {
              "dayOfWeek": 0,
              "state": "UNKNOWN",
              "openTime": null,
              "closeTime": null
            },
            {
              "dayOfWeek": 1,
              "state": "OPEN",
              "openTime": "09:00",
              "closeTime": "22:00"
            },
            {
              "dayOfWeek": 2,
              "state": "UNKNOWN",
              "openTime": null,
              "closeTime": null
            },
            {
              "dayOfWeek": 3,
              "state": "UNKNOWN",
              "openTime": null,
              "closeTime": null
            },
            {
              "dayOfWeek": 4,
              "state": "UNKNOWN",
              "openTime": null,
              "closeTime": null
            },
            {
              "dayOfWeek": 5,
              "state": "UNKNOWN",
              "openTime": null,
              "closeTime": null
            },
            {
              "dayOfWeek": 6,
              "state": "UNKNOWN",
              "openTime": null,
              "closeTime": null
            }
          ]
        }
      ]
    }
  },
  "meta": {
    "requestId": "req-demo-001",
    "generatedAt": "2026-11-01T22:45:00+09:00",
    "warnings": [
      {
        "code": "WEEKLY_SCHEDULE_ONLY",
        "message": "정규 운영시간 기준입니다. 공휴일·임시휴관과 예약 여부는 별도 확인하세요.",
        "facilityId": null
      },
      {
        "code": "AMENITIES_NOT_AVAILABLE",
        "message": "현재 데이터 모델에는 편의시설 정보가 없습니다.",
        "facilityId": "101"
      }
    ]
  }
}
```

예시에서 화요일 이외 값이 UNKNOWN인 것은 문서용 결측 표현이다. 실제 자료에 있는 요일을 UNKNOWN으로 바꾸라는 의미가 아니다. selectedSchedule은 해당 공간 weeklyHours의 선택 요일과 같아야 한다.

상세는 시간 조건에 맞지 않아도 시설이 존재하면 200이다. AI에서 닫힌 공간을 상세로 연결할 때도 같은 규칙이다. 미니맵은 FacilityBase.coordinates로 그린다. 교통 배지는 A05를 별도로 호출해 실패가 시설 상세를 지우지 않게 한다.

#### 상태코드

| HTTP | code / 상황 | 처리 |
|---|---|---|
| 200 | 시설·선택 공간 조회 성공 | AVAILABLE/UNAVAILABLE/UNKNOWN 모두 포함 가능 |
| 400 | INVALID_REQUEST / INVALID_TIME_RANGE | 필수·형식·시간 오류 |
| 404 | FACILITY_NOT_FOUND | 유효한 ID의 시설 없음 |
| 503 | DATA_UNAVAILABLE | 필수 저장소 불가 |

ALWAYS_OPEN은 DEFAULT 가상 공간만 가진다. 예약 기능을 호출하거나 예약 여부를 확인하는 API가 아니라 상세 운영정보를 제공하는 API다.

---

## A04. 공간별 불일치 분석 및 AI 개선안

공통 필드·성공/오류 envelope는 「공통 규칙·응답 타입」을 따릅니다. 스키마 보완과 미정 정책은 「DB 매핑·보완 및 논의 사항」에서 확인합니다. 현재 상태는 계약 검토 중입니다.

- `POST /api/v1/improvement-analyses`
- Path / Query: 없음. 인증: 추가 논의 필요.
- Headers: `Content-Type: application/json`, `Accept: application/json`.
- 요청 Body: criteria 객체 필수, 내부는 3.6 Criteria와 동일.
- 동기 처리 후 200 응답 제안. 캐시 DB 행을 새로 만들더라도 이 API의 의미는 분석 결과 조회/생성이므로 201로 구분하지 않는다.
- AI 실행이 길어지면 202 작업 생성+상태조회 구조가 필요할 수 있지만 현재 요구에 없는 API를 확정하지 않는다.

```json
{
  "criteria": {
    "keyword": null,
    "district": "수지구",
    "facilityTypeIds": [
      1
    ],
    "dayOfWeek": 1,
    "startTime": "19:00",
    "endTime": "21:00"
  }
}
```

#### 분석 대상

A02의 열린 시설 목록을 그대로 입력받지 않는다. 같은 키워드·지역·유형 후보에서 공간별 UNAVAILABLE 항목을 다시 찾는다. 한 시설의 열람실이 열려 있어 A02에 포함되더라도 종합자료실이 닫혀 있으면 해당 종합자료실은 개선 후보가 될 수 있다.

- OPEN_HOURS: 실제 운영공간별 분석.
- RESERVATION: 운영시간 불일치는 분석할 수 있지만 예약 가능성은 판단하지 않는다. 제안에 예약 필요를 함께 명시한다.
- ALWAYS_OPEN: 일내 운영시간 불일치가 없으므로 운영시간 연장 후보에서 제외한다.
- UNKNOWN: 임의 휴관으로 추측하지 않고 제외 수로 알린다.
- 같은 시설에 개선 대상 공간이 여러 개면 items가 여러 개일 수 있다. `facilityId + spaceType`으로 구별한다. 시설명만 보고 중복 제거하지 않는다.
- 후보 순위·최대 개수는 미정이다. 본 초안은 전체 후보를 반환하는 계약이므로 시간/비용 때문에 일부만 처리하려면 후보 선택·페이지네이션 규칙을 먼저 합의한다.

#### 캐시 처리 순서

1. 현재 운영시간으로 불일치 구간을 먼저 계산한다. 현재 AVAILABLE인 대상에는 과거 개선 캐시를 보여주지 않는다.
2. `(facility_id, space_type, day_of_week, req_start, req_end)`로 캐시를 조회한다.
3. 행이 있고 내용·유효성이 검증되면 HIT로 반환하며 LLM을 다시 호출하지 않는다.
4. 행이 없거나 무효화되었으면 현재 운영시간과 bus_stop_count 등 검증된 사실로 LLM에 요청한다.
5. options는 JSON 배열 형식으로 검증하고, suggested_close가 현재 구간에 맞는지 검증한다. 요약에 정류장 수 등 원천과 다른 숫자가 없는지도 확인한다.
6. 검증된 결과를 ai_suggestion에 저장한다. 동시 동일 요청은 UNIQUE 충돌을 정상적으로 처리해 저장된 행을 다시 읽는다. DB UNIQUE만으로 중복 LLM 과금까지 막히지는 않아 동시 실행 제어 방식은 별도 합의한다.
7. LLM 실패 시 현재 운영시간·불일치 구간은 유지한다. 캐시 쓰기만 실패하면 생성 결과는 반환하고 저장 실패를 경고한다.

#### 응답 data 필드

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| data.criteria | Criteria | 예 | 아니오 | 정규화한 여섯 조건 |
| data.candidateCount | integer | 예 | 아니오 | 개선 대상 시설×공간 수=items.length |
| data.facilityCount | integer | 예 | 아니오 | items 내 서로 다른 facilityId 수 |
| data.unknownSpaceCount | integer | 예 | 아니오 | 동일 기본 후보 중 시간 미확인으로 제외한 공간 수 |
| data.items | ImprovementItem[] | 예 | 아니오 | 공간별 제안 |
| data.disclaimer | string | 예 | 아니오 | AI 의견은 참고용이며 실제 운영시간·확정 정책이 아니라는 안내 |

#### ImprovementItem

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| facility | FacilityBase | 예 | 아니오 | 대상 시설. id/name/accessType/이미지 등 |
| spaceType | string(enum) | 예 | 아니오 | 대상 공간, 캐시 키 포함 |
| spaceLabel | string | 예 | 아니오 | 공간 표시명 |
| selectedSchedule | DaySchedule | 예 | 아니오 | 현재 선택 요일 운영시간 |
| availability | Availability | 예 | 아니오 | UNAVAILABLE. 미운영 구간은 이 객체 안에만 둠 |
| currentClosingTime | string(time) | 예 | 예 | OPEN이면 closeTime과 동일, CLOSED이면 null |
| proposedClosingTime | string(time) | 예 | 예 | 제안 종료. 휴관/조기개방 등 종료 연장만으로 해결 못하면 null 가능 |
| proposalStatus | string(enum) | 예 | 아니오 | GENERATED / UNAVAILABLE |
| proposalReason | string | 예 | 예 | 제안 설명 또는 실패 사유. 현재 스키마에 별도 저장하지 않는 요청별 설명 |
| alternatives | string[] | 예 | 아니오 | options의 파싱 결과. 생성 실패면 [] |
| integratedSuggestion | IntegratedSuggestion | 예 | 아니오 | 운영·교통 종합 제안 |
| transport | TransportAccessibility | 예 | 아니오 | A05와 동일 타입 |
| weakTimeAnalysis | WeakTimeAnalysis | 예 | 아니오 | 요구사항 유지, 현재 데이터 미지원 상태 표시 |
| suggestionId | string | 예 | 예 | 유효한 DB 캐시 ID. 저장된 행이 없으면 null |
| cache | CacheInfo | 예 | 아니오 | 캐시 조회/저장 결과 |
| limitations | string[] | 예 | 아니오 | 실제 제약·추가 검토 필요사항 |

proposalStatus=GENERATED는 새 생성 또는 검증된 캐시 재사용 모두 포함한다. 생성 완료 행은 alternatives에 운영 대안을 포함한다. 종료 연장이 적절하지 않으면 proposedClosingTime은 null이고 대안에 조기 개방/휴관일 시범 운영 등 제안을 담을 수 있다. 이는 시행 가능성이 행정적으로 검증되었다는 뜻이 아니다.

현재 DB에 proposalReason/limitations 필드가 없어 요청 시 일정 차이와 확인된 제약을 바탕으로 구성한다. 자유 형식의 별도 LLM 문장을 재현해야 한다면 캐시 컬럼을 확장해야 하며, 저장되지 않은 과거 LLM 근거를 만들어내지 않는다.

#### CacheInfo

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| status | string(enum) | 예 | 아니오 | HIT / CREATED / NOT_STORED / UNAVAILABLE |
| createdAt | string(date-time) | 예 | 예 | HIT/CREATED는 해당 캐시 created_at, 나머지는 null |

- HIT: 유효 캐시를 재사용. suggestionId와 createdAt은 non-null.
- CREATED: 이번 요청에서 생성 후 저장 성공. suggestionId와 createdAt은 non-null.
- NOT_STORED: 생성 결과는 있으나 저장 실패. suggestionId/createdAt은 null, CACHE_WRITE_FAILED 경고.
- UNAVAILABLE: 유효 캐시/생성 결과가 없음. suggestionId/createdAt은 null, proposalStatus=UNAVAILABLE.

cache 상태는 DB에 별도 저장할 필드가 아니라 이 요청 처리 결과다. 캐시 조회 실패 시에도 현재 시설·운영 정보 조회가 성공했다면 생성 시도를 허용하는 정책을 제안하며 CACHE_READ_FAILED를 남긴다. 실패 상태나 손상된 options를 정상 캐시로 저장/재사용하지 않는다.

#### IntegratedSuggestion

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| status | string(enum) | 예 | 아니오 | COMPLETE / PARTIAL / UNAVAILABLE |
| text | string | 예 | 예 | COMPLETE/PARTIAL은 문장, UNAVAILABLE은 null |

COMPLETE는 운영시간과 확보된 교통 근거를 함께 반영했다는 뜻이다. bus_stop_count만 있다면 ‘500m 내 정류장 N개’라는 공간 정보까지만 사용할 수 있다. 실제 배차/귀가 가능을 검증했다는 뜻은 아니다. 교통 근거가 없으면 PARTIAL이며 원문 P0 통합 개선안의 완전 구현으로 간주하지 않는다.

캐시 테이블에는 status/근거 버전이 없다. 따라서 현재 정류장 수가 있다는 이유만으로 과거 summary를 COMPLETE로 바꾸면 안 된다. 운영/교통/프롬프트 변경 시 관련 캐시를 무효화하는 계약이 선행되어야 한다. 유효성 확인이 안 되는 기존 캐시는 재생성하고, 이를 피하려면 근거 버전·생성 상태 컬럼을 추가해야 한다.

#### WeakTimeAnalysis — 원문 P1 유지 / 테이블 보완 필요

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| status | string(enum) | 예 | 아니오 | 현재 모델은 NOT_SUPPORTED. 확장 시 AVAILABLE / NO_DATA 추가 사용 |
| periods | WeakTimePeriod[] | 예 | 아니오 | 현재 모델은 []. 운행 근거 확보·산식 합의 후 제공 |
| WeakTimePeriod.dayOfWeek | integer | 예 | 아니오 | 0~6 |
| WeakTimePeriod.startTime | string(time) | 예 | 아니오 | 취약 구간 시작 |
| WeakTimePeriod.endTime | string(time) | 예 | 아니오 | 취약 구간 종료 |
| WeakTimePeriod.cause | string | 예 | 아니오 | 실제 데이터 기반 원인 |
| WeakTimePeriod.evidence | string | 예 | 아니오 | 근거 데이터 설명; 임의 수치 금지 |

이는 기능을 삭제한 것이 아니다. P1 취약 시간 기능을 구현하기 위한 후속 스키마/데이터 확보가 남아 있다는 표현이다. ‘21시 이후 배차간격 24분’은 원문의 예시이며 현재 스키마만으로 산출할 수 없다.

#### 응답 예시 — 동일 조건의 캐시 재사용

```json
{
  "data": {
    "criteria": {
      "keyword": null,
      "district": "수지구",
      "facilityTypeIds": [
        1
      ],
      "dayOfWeek": 1,
      "startTime": "19:00",
      "endTime": "21:00"
    },
    "candidateCount": 1,
    "facilityCount": 1,
    "unknownSpaceCount": 0,
    "items": [
      {
        "facility": {
          "facilityId": "101",
          "typeId": 1,
          "typeName": "도서관",
          "district": "수지구",
          "accessType": "OPEN_HOURS",
          "name": "예시 공공도서관",
          "address": "용인시 수지구 예시 주소",
          "imageUrl": null,
          "coordinates": {
            "latitude": 37.32,
            "longitude": 127.1
          }
        },
        "spaceType": "GENERAL",
        "spaceLabel": "종합자료실",
        "selectedSchedule": {
          "dayOfWeek": 1,
          "state": "OPEN",
          "openTime": "09:00",
          "closeTime": "18:00"
        },
        "availability": {
          "status": "UNAVAILABLE",
          "basis": "WEEKLY_SCHEDULE",
          "requiresReservation": false,
          "unavailableIntervals": [
            {
              "startTime": "19:00",
              "endTime": "21:00"
            }
          ]
        },
        "currentClosingTime": "18:00",
        "proposedClosingTime": "21:00",
        "proposalStatus": "GENERATED",
        "proposalReason": "종합자료실의 운영 종료가 희망 이용 시작보다 빠릅니다.",
        "alternatives": [
          "화요일 종합자료실을 21시까지 시범 운영하는 방안을 검토합니다. 인력·예산 검토가 필요합니다."
        ],
        "integratedSuggestion": {
          "status": "COMPLETE",
          "text": "종합자료실의 21시까지 시범 운영을 검토할 수 있습니다. 시설 500m 내 정류장은 4개이며, 실제 야간 운행과 귀가 가능 여부는 별도 확인이 필요합니다."
        },
        "transport": {
          "status": "AVAILABLE",
          "radiusMeters": 500,
          "stopCount": 4,
          "grade": null,
          "notes": [
            "등급 기준은 추가 논의가 필요합니다.",
            "정류장 수는 시간대별 운행 가능성을 의미하지 않습니다."
          ]
        },
        "weakTimeAnalysis": {
          "status": "NOT_SUPPORTED",
          "periods": []
        },
        "suggestionId": "301",
        "cache": {
          "status": "HIT",
          "createdAt": "2026-11-01T21:00:00+09:00"
        },
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
      {
        "code": "WEEKLY_SCHEDULE_ONLY",
        "message": "정규 운영시간 기준입니다. 공휴일·임시휴관과 예약 여부는 별도 확인하세요.",
        "facilityId": null
      }
    ]
  }
}
```

위 HIT 예시는 현재 근거에 대한 캐시 유효성 검증이 된 경우다. 실제 ai_suggestion 행을 조회했다는 뜻이 아니다.

#### 제안 대상 없음 — 200

```json
{
  "data": {
    "criteria": {
      "keyword": null,
      "district": "수지구",
      "facilityTypeIds": [
        1
      ],
      "dayOfWeek": 1,
      "startTime": "19:00",
      "endTime": "21:00"
    },
    "candidateCount": 0,
    "facilityCount": 0,
    "unknownSpaceCount": 0,
    "items": [],
    "disclaimer": "AI 제안은 참고 의견이며 공식 운영시간이나 확정된 정책이 아닙니다. 실제 이용 전 공식 홈페이지 또는 전화로 확인하세요."
  },
  "meta": {
    "requestId": "req-demo-001",
    "generatedAt": "2026-11-01T22:45:00+09:00",
    "warnings": [
      {
        "code": "WEEKLY_SCHEDULE_ONLY",
        "message": "정규 운영시간 기준입니다. 공휴일·임시휴관과 예약 여부는 별도 확인하세요.",
        "facilityId": null
      }
    ]
  }
}
```

후보가 없으면 LLM 호출 없이 반환한다. unknownSpaceCount>0이면 ‘개선할 곳이 전혀 없다’가 아니라 일부 미판정 상태임을 함께 안내한다.

#### LLM 실패 시 변경되는 item 필드 발췌

```json
{
  "proposedClosingTime": null,
  "proposalStatus": "UNAVAILABLE",
  "proposalReason": "AI 제안을 생성하지 못했습니다.",
  "alternatives": [],
  "integratedSuggestion": {
    "status": "UNAVAILABLE",
    "text": null
  },
  "suggestionId": null,
  "cache": {
    "status": "UNAVAILABLE",
    "createdAt": null
  }
}
```

계산된 facility/spaceType/selectedSchedule/availability/transport는 유지하고 meta.warnings에 LLM_UNAVAILABLE을 넣는다. 전체 응답은 200이다. 시설 DB 자체를 조회하지 못하면 503이다. 부분 실패를 candidateCount=0으로 바꾸거나 실제 생성이 없는데 GENERATED로 표시하지 않는다.

#### 상태코드

| HTTP | code / 상황 | 처리 |
|---|---|---|
| 200 | 캐시 HIT / 새 생성 / 후보 없음 | 정상 결과 |
| 200 | LLM·캐시 일부 실패 | item 상태와 warnings로 구분 |
| 400 | INVALID_REQUEST / INVALID_TIME_RANGE | JSON·기본 조건 오류 |
| 415 | UNSUPPORTED_MEDIA_TYPE | JSON 본문 형식 필요 |
| 503 | DATA_UNAVAILABLE | 필수 DB 조회 불가 |

단순 스크롤 이벤트마다 자동 생성하지 않는다. FE는 같은 조건의 진행 중 요청을 공유하고, 조건 변경 후 이전 응답이 새 화면을 덮어쓰지 않게 한다. 프론트에서 캐시 저장용 API를 따로 호출할 필요는 없다. suggestionId를 반환한다고 상세 조회/삭제 API가 자동으로 생기는 것도 아니다.

---

## A05. 시설 교통 접근성 조회

공통 필드·성공/오류 envelope는 「공통 규칙·응답 타입」을 따릅니다. 스키마 보완과 미정 정책은 「DB 매핑·보완 및 논의 사항」에서 확인합니다. 현재 상태는 계약 검토 중입니다.

- `GET /api/v1/facilities/{facilityId}/transport-accessibility`
- Path facilityId: string, 필수. Query / Body: 없음. 인증: 추가 논의 필요.
- P1 조건부 API. facility.bus_stop_count 사전 적재값을 조회한다.
- 500m는 원문 요구에 맞춘 반경이며, 사용자 변경 파라미터를 추가하지 않는다.

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
| stopCount | integer | 예 | 예 | facility.bus_stop_count, 0 이상 |
| grade | TransportGrade | 예 | 예 | 기준 미정/자료 없음이면 null |
| notes | string[] | 예 | 아니오 | 자료/판정의 한계 |
| TransportGrade.code | string | 예 | 아니오 | 등급 코드 집합은 추가 논의 |
| TransportGrade.label | string | 예 | 아니오 | 등급 표시명 |
| TransportGrade.ruleVersion | string | 예 | 아니오 | 등급 산식 합의 후 버전 지정 |

AVAILABLE은 유효한 정류장 개수가 있다는 뜻이다. null은 미확인, 0은 확인 결과 0개다. 등급은 합의된 산식이 있어야 제공하며 정류장 수가 있다고 자동으로 ‘양호’라고 정하지 않는다. grade=null인 동안 원문 등급 배지 기능은 미완료 상태다.

현재 DB에는 정류장 자료 기준일이 없으므로 facility.data_updated_at을 교통 최신성의 날짜로 재사용하지 않는다.

```json
{
  "data": {
    "facilityId": "101",
    "accessibility": {
      "status": "AVAILABLE",
      "radiusMeters": 500,
      "stopCount": 4,
      "grade": null,
      "notes": [
        "등급 기준은 추가 논의가 필요합니다.",
        "정류장 수는 시간대별 운행 가능성을 의미하지 않습니다."
      ]
    }
  },
  "meta": {
    "requestId": "req-demo-001",
    "generatedAt": "2026-11-01T22:45:00+09:00",
    "warnings": [
      {
        "code": "GRADE_RULE_NOT_DEFINED",
        "message": "정류장 수는 제공하지만 등급 기준은 미정입니다.",
        "facilityId": "101"
      }
    ]
  }
}
```

```json
{
  "data": {
    "facilityId": "101",
    "accessibility": {
      "status": "NO_DATA",
      "radiusMeters": 500,
      "stopCount": null,
      "grade": null,
      "notes": [
        "500m 정류장 집계값이 적재되지 않았습니다."
      ]
    }
  },
  "meta": {
    "requestId": "req-demo-001",
    "generatedAt": "2026-11-01T22:45:00+09:00",
    "warnings": [
      {
        "code": "TRANSPORT_DATA_UNAVAILABLE",
        "message": "교통 정보를 확인할 수 없습니다.",
        "facilityId": "101"
      }
    ]
  }
}
```

#### 상태코드

| HTTP | code / 상황 | 처리 |
|---|---|---|
| 200 | count 존재 | AVAILABLE, 0도 정상 |
| 200 | bus_stop_count=null | NO_DATA, 0으로 대체하지 않음 |
| 400 | INVALID_REQUEST | facilityId 형식 오류 |
| 404 | FACILITY_NOT_FOUND | 시설 없음 |
| 503 | FEATURE_NOT_ENABLED | 교통 API 비활성 |
| 503 | DATA_UNAVAILABLE | 시설 DB 조회 실패 |

A05가 비활성이면 FE는 이 기능을 노출하지 않는다. A04에서는 transport.status=NOT_ENABLED로 부분 결과를 유지할 수 있다. 이는 교통 P1을 생략해도 통합 개선안 P0가 자동 완성된다는 의미는 아니다. 정류장 수 계산은 ETL 단계이며 조회 중 공급자 API에 재요청하지 않는 방향이다.
