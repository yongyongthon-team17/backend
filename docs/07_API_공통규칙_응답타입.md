# 공통 규칙·응답 타입

v0.2 · 기능명세서 기준 · 계약 검토 중

## 3. 공통 규칙과 오류 형식

### 3.1 기본 계약 — 제안

| 항목 | 규칙 |
|---|---|
| Base URL | 배포 주소 미정; `/api/v1`은 버전 경로 제안 |
| JSON | UTF-8, camelCase. DB snake_case를 API에 그대로 노출하지 않음 |
| 헤더 | `Accept: application/json`; A04는 `Content-Type: application/json` 추가 |
| 인증 | 사용자 테이블이 없다고 무인증을 확정하지 않음. JWT/세션/쿠키/키 요구는 추가 논의 |
| BIGINT ID | facilityId·suggestionId는 10진수 문자열. typeId는 정수 |
| 시간대 | 사용자 시간 조건은 Asia/Seoul 제안. DB 저장 시간대는 미정 |
| 시간 형식 | HH:mm. 초 단위 원천은 손실 없이 변환 가능한지 검증; 임의 반올림 금지 |
| null | 필수 키와 null 허용은 별개. 표에 필수=예이면 키 유지 |
| 요청 기본값 | 검색 keyword·district 생략=없음/전체, facilityTypeIds 생략=(원문 값 누락 — 3.6 기준 '전체'). 공간은 시설 내부에서 판정 |
| 조회 결과 규모 | 초안은 전체 결과 반환; 합의 없이 상위 N건으로 자르지 않음. 페이지네이션은 규모 측정 후 결정 |
| 정렬 | 시설은 facilityId 숫자값 오름차순, 공간은 GENERAL→READING→DEFAULT 제안 |
| 지역/유형/공간 | 키워드·지역·시간·유형 간 AND; facilityTypeIds 내부는 OR 제안. 공간 검색 필터는 추가하지 않음 |

API 전체의 구조는 검토용 계약이다. 새로운 보안/성능 한도나 기술 스택을 확정한 것이 아니다.

### 3.2 코드 정의

| 필드 | 값 | 의미 |
|---|---|---|
| district | 수지구 / 기흥구 / 처인구 | 생략하면 용인시 전체 |
| dayOfWeek | 0 / 1 / 2 / 3 / 4 / 5 / 6 | 월 / 화 / 수 / 목 / 금 / 토 / 일 |
| spaceType | GENERAL | 종합자료실 |
| spaceType | READING | 열람실 |
| spaceType | DEFAULT | 작은도서관·체육시설 등의 단일 공간 |
| accessType | OPEN_HOURS | 운영시간 내 방문 |
| accessType | RESERVATION | 운영시간 내라도 별도 예약 필요 |
| accessType | ALWAYS_OPEN | 상시 이용. operating_hour 행 없음 |

일반 공간 목록은 해당 시설의 operating_hour 전체 요일에 존재하는 space_type을 기준으로 구성한다. 요청 요일 행만 보고 공간 자체를 없애지 않는다. 운영시간 행이 전혀 없는 OPEN_HOURS/RESERVATION 시설에는 계약상 DEFAULT/UNKNOWN을 안내용으로 구성하는 방식을 제안한다. 이것은 실제 DEFAULT 공간 존재를 확인했다는 뜻이 아니며 AI 후보로 사용하지 않는다.

ALWAYS_OPEN은 API에서 DEFAULT 가상 공간을 구성한다. DB에 실제 operating_hour 행을 추가했다는 뜻은 아니다. 해당 시설이 실제로 24시간 이용 가능한지 적재 시 검증해야 한다.

### 3.3 시간 판정과 예약 안내

1. 사용자 희망 시간 전체가 운영시간 안에 있어야 한다. 구간은 `[startTime, endTime)` 제안이다.
2. 시작은 00:00~23:59, 종료는 00:01~24:00이며 start<end. 24:00은 일 종료 표시 제안이다. DBMS TIME 및 캐시 키 변환은 구현 전에 확인해야 한다.
3. 운영 09:00–21:00 / 희망 19:00–21:00 → AVAILABLE.
4. 운영 09:00–20:00 / 희망 19:00–21:00 → UNAVAILABLE, 미운영 20:00–21:00.
5. is_closed=true → CLOSED/UNAVAILABLE, 요청 전체가 미운영.
6. 행 없음·open/close null·역전 구간 등은 UNKNOWN. is_closed=false만으로 운영 중이라고 판단하지 않는다.
7. 현재 모델은 하루 한 구간이다. 오전/오후 사이 휴게가 있는 원천을 하나의 긴 시간으로 합쳐 ‘이용 가능’으로 판정하지 않는다. 별도 확장 전에는 해당 구간 판정을 보류하고 UNKNOWN으로 처리한다.
8. ALWAYS_OPEN → 정규화된 OPEN 00:00–24:00, availability.basis=ACCESS_TYPE. 운영시간 행이 없다는 이유로 UNKNOWN 처리하지 않는다.
9. RESERVATION은 시간 조건을 만족하면 availability.status=AVAILABLE이지만 requiresReservation=true다. 이는 **운영시간 적합**을 뜻하며 예약 가능/확정을 뜻하지 않는다. FE 배지는 ‘예약 필요’로 표시한다.
10. API 판정은 주간 정규시간이다. 주차별 휴관/공휴일/임시휴관을 판정하지 못한다. closedDayNote와 공통 주의 문구를 함께 표시한다.
11. 한 시설의 GENERAL이 18시에 닫히고 READING이 22시에 닫히면 19~21시 검색은 READING만 매칭된다. GENERAL의 영업으로 대체하거나 시설의 최대 마감시각만 사용하지 않는다.

### 3.4 공통 성공 응답

```json
{
  "data": {},
  "meta": {
    "requestId": "req-demo-001",
    "generatedAt": "2026-11-01T22:45:00+09:00",
    "warnings": []
  }
}
```

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| data | object | 예 | 아니오 | API별 데이터 |
| meta | object | 예 | 아니오 | 메타데이터 |
| meta.requestId | string | 예 | 아니오 | 요청 추적 ID; 영속 조회용 ID가 아님 |
| meta.generatedAt | string(date-time) | 예 | 아니오 | 응답 생성 시각 |
| meta.warnings | Warning[] | 예 | 아니오 | 없으면 [] |
| Warning.code | string | 예 | 아니오 | 기계 처리용 코드 |
| Warning.message | string | 예 | 아니오 | 설명 |
| Warning.facilityId | string | 예 | 예 | 특정 시설 경고 또는 전체 경고 null |

경고 코드 제안: WEEKLY_SCHEDULE_ONLY, UNKNOWN_HOURS_EXCLUDED, AMENITIES_NOT_AVAILABLE, TRANSPORT_DATA_UNAVAILABLE, GRADE_RULE_NOT_DEFINED, LLM_UNAVAILABLE, CACHE_READ_FAILED, CACHE_WRITE_FAILED, CACHE_INVALIDATED. AI 출처·캐시의 신뢰 여부가 불명확하면 성공 내용으로 위장하지 않는다.

### 3.5 공통 오류 응답

```json
{
  "error": {
    "code": "INVALID_TIME_RANGE",
    "message": "종료 시간은 시작 시간보다 늦어야 합니다.",
    "details": [
      {
        "field": "endTime",
        "reason": "MUST_BE_AFTER_START_TIME"
      }
    ],
    "requestId": "req-demo-error"
  }
}
```

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| error | object | 예 | 아니오 | 오류 |
| error.code | string | 예 | 아니오 | 오류 코드 |
| error.message | string | 예 | 아니오 | 표시 가능한 메시지 |
| error.details | ErrorDetail[] | 예 | 아니오 | 없으면 [] |
| ErrorDetail.field | string | 예 | 아니오 | 필드 경로 |
| ErrorDetail.reason | string | 예 | 아니오 | 검증 이유 코드 |
| error.requestId | string | 예 | 아니오 | 추적 ID |

| HTTP | code | 사용 조건 |
|---|---|---|
| 400 | INVALID_REQUEST | JSON 파싱·필수 필드·타입·요일/코드·좌표 형식·시설 ID 형식 오류 |
| 400 | INVALID_TIME_RANGE | 종료<=시작, 형식 또는 시각 범위 오류 |
| 404 | FACILITY_NOT_FOUND | 유효 형식의 ID지만 시설 없음 |
| 405 | METHOD_NOT_ALLOWED | 지원하지 않는 HTTP Method; Allow 헤더 |
| 415 | UNSUPPORTED_MEDIA_TYPE | A04에서 JSON 이외 Content-Type |
| 429 | RATE_LIMIT_EXCEEDED | 요청 제한 정책을 채택하고 사용자 요청이 실제 초과했을 때만; Retry-After |
| 500 | INTERNAL_ERROR | 예상하지 못한 서버 오류 |
| 503 | DATA_UNAVAILABLE | 필수 DB/시설 자료를 사용할 수 없어 결과 생성 불가 |
| 503 | FEATURE_NOT_ENABLED | A05 교통 기능 비활성 |

모든 API는 405/500 공통, 429는 한도 정책 채택 시만 적용한다. 401/403은 인증·권한 정책 확정 후 추가한다. 운영 DB에서 조회하는 A01/A02/A03/A05에 공공데이터 공급자 502/504를 무조건 나열하지 않는다. 공급자 적재 실패와 사용자 조회 실패는 분리한다. A04는 시설/시간 계산이 성공한 뒤 LLM이 실패하면 200 부분 응답을 제안한다.

### 3.6 공통 요청 Criteria

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| keyword | string | 아니오 | 예(JSON) | 시설명 OR 주소 포함 검색; 양끝 공백 제거, 생략/null/공백만 입력은 null 제안 |
| district | string(enum) | 아니오 | 예(JSON) | 구 이름. 생략/null=용인시 전체 |
| facilityTypeIds | integer[] | 아니오 | 아니오 | 생략/[]=전체; A01에 존재하는 양수 typeId. 중복 제거 |
| dayOfWeek | integer | 예 | 아니오 | 0~6, 오늘 초기값도 FE가 명시 전송 |
| startTime | string(time) | 예 | 아니오 | HH:mm 시작 |
| endTime | string(time) | 예 | 아니오 | HH:mm 종료 |

A02에서는 Query, A04에서는 JSON criteria로 사용한다. 응답 criteria는 위 여섯 키를 모두 포함한다. keyword/district만 null 가능하다. Query에서 null 문자열이나 ALL 코드를 보내지 않고 해당 키를 생략한다. 배열은 `facilityTypeIds=1&facilityTypeIds=2` 형태로 반복 전달한다. 유형 복수 선택은 원문 미확정이므로 채택 여부를 논의한다. 공간 선택 UI는 원문에 없어 추가하지 않는다.

### 3.7 공통 응답 타입

#### DaySchedule

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| dayOfWeek | integer | 예 | 아니오 | 0~6 |
| state | string(enum) | 예 | 아니오 | OPEN / CLOSED / UNKNOWN |
| openTime | string(time) | 예 | 예 | OPEN일 때 필수 값, CLOSED/UNKNOWN이면 null |
| closeTime | string(time) | 예 | 예 | OPEN일 때 필수 값, CLOSED/UNKNOWN이면 null |

휴관일에 원천의 open/close 값이 남아 있어도 CLOSED로 판정한다. UNKNOWN은 원천을 추측해 시각을 채우지 않는다. ALWAYS_OPEN의 00:00~24:00은 accessType에서 만든 표현이며 원천 TIME 컬럼의 값이 아니다.

#### Interval 및 Availability

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| Interval.startTime | string(time) | 예 | 아니오 | 미운영 구간 시작 |
| Interval.endTime | string(time) | 예 | 아니오 | 미운영 구간 종료 |
| Availability.status | string(enum) | 예 | 아니오 | AVAILABLE / UNAVAILABLE / UNKNOWN |
| Availability.basis | string(enum) | 예 | 아니오 | WEEKLY_SCHEDULE / ACCESS_TYPE |
| Availability.requiresReservation | boolean | 예 | 아니오 | accessType=RESERVATION이면 true, 실제 예약 확인 아님 |
| Availability.unavailableIntervals | Interval[] | 예 | 예 | AVAILABLE=[], UNAVAILABLE=1개 이상, UNKNOWN=null |

#### FacilityBase

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| facilityId | string | 예 | 아니오 | BIGINT의 10진수 문자열 |
| typeId | integer | 예 | 아니오 | 시설 유형 ID |
| typeName | string | 예 | 아니오 | facility_type.name |
| district | string(enum) | 예 | 아니오 | 구 이름 |
| accessType | string(enum) | 예 | 아니오 | 이용 방식 |
| name | string | 예 | 아니오 | 시설명 |
| address | string | 예 | 아니오 | 주소 |
| imageUrl | string(uri) | 예 | 예 | 유형별 기본 이미지 URL; 경로 없으면 null |
| coordinates | object | 예 | 아니오 | 시설 좌표 |
| coordinates.latitude | number | 예 | 아니오 | 위도 -90~90 |
| coordinates.longitude | number | 예 | 아니오 | 경도 -180~180 |

#### SpaceAvailability

| 필드 | 타입 | 필수 | null | 설명 |
|---|---|---|---|---|
| spaceType | string(enum) | 예 | 아니오 | 분석/검색 대상 공간 |
| spaceLabel | string | 예 | 아니오 | 종합자료실 / 열람실 / 단일 공간 |
| selectedSchedule | DaySchedule | 예 | 아니오 | 선택 요일 운영시간 |
| availability | Availability | 예 | 아니오 | 선택 시간 판정 |
