# 지금가용 백엔드 (용용톤 2026-11-15)

AI 기반 시간대별 생활 인프라 접근성 분석 서비스. 용인시 공공데이터(시설, 운영시간, 교통) 사용.
트랙: 여가 생활 및 인프라. 행사 당일(2026-11-15)까지 MVP 완성이 목표.

## 스택
- Java 21, Spring Boot 4.1.2, Gradle(Groovy), Spring Data JPA, Validation, Lombok
- 설정은 YAML (application.yaml, application-local.yaml, application-prod.yaml)
- DB: 개발 H2(PostgreSQL 호환 모드), 운영 PostgreSQL
- 패키지 루트: com.jigeumgayong.backend
- AI: OpenAI API (중앙 해커톤 지급 크레딧 사용). 분석 결과는 ai_analysis 테이블에 캐시
  - 모델: 개발·테스트 gpt-6-luna, 시연 gpt-6.1-terra, 추론 수준(reasoning) low. 품질 부족 시에만 gpt-6.1-sol
  - 호출은 Responses API 기준. 구현 직전에 모델 ID와 사용법을 OpenAI 공식 문서로 다시 확인할 것
  - JSON 응답 안정성은 첫 호출에서 확인하고, 구조화 출력 지원 여부가 불확실하면 질문할 것
  - API 키는 환경 변수(OPENAI_API_KEY)로만 읽고, 코드·yml·레포에 넣지 말 것
  - 모델명·추론 수준은 설정값(프로파일별)으로 빼고 ai_analysis.model_name에 저장
  - 응답은 JSON으로만 받아 서버에서 검증한 뒤 저장(R09). 검증 실패 결과는 저장하지 않음
  - 호출 실패·타임아웃 시 다른 API는 영향받지 않게 하고, 크레딧 소진을 막기 위해 캐시 HIT면 재호출하지 않음
- 배포: [미정]
- 지도는 프론트의 Kakao Map SDK (백엔드는 좌표만 제공)

## 문서 (docs/)
01 공공데이터 조사 / 02 요구사항 / 03 아키텍처 / 04 기능명세 / 05 화면설계
06 API 명세 / 07 API 공통규칙·응답타입 / 08 DB 매핑·보완(R01~R18)
09 외부연동·요구사항추적·검증기준 / 10 데이터테이블 v2

작업 전에 해당 문서를 직접 읽을 것. 예: API 구현은 06·07, 엔티티는 10, 테스트는 09.

## 문서 우선순위 (중요)
- DB 스키마의 기준은 **10번(데이터테이블 v2)**.
- 06·07번 API 명세는 v0.2로 구 스키마(ai_suggestion, TIME 컬럼) 기준이라 10번과 어긋나는 부분이 있음.
  어긋나면 08번 R01~R18을 따르고, 그래도 불명확하면 임의로 고르지 말고
  충돌 지점을 보여준 뒤 질문할 것.
- 문서에 없는 내용은 추측하지 말고 "문서에 없음"이라고 말한 뒤 선택지를 제안할 것.
- 문서가 PostgreSQL 기준으로 이미 수정되어 있음. MySQL 문법(AUTO_INCREMENT, ON UPDATE 등)을 쓰지 말 것.

## DB 규칙 (PostgreSQL)
- PK는 @GeneratedValue(strategy = GenerationType.IDENTITY)
- 운영시간은 TIME이 아니라 분 단위 SMALLINT(open_min, close_min). 24:00 = 1440. API에서는 "HH:mm"으로 변환
- 요일은 DayOfWeek.getValue() - 1 (0=월 … 6=일)
- ai_analysis.payload는 JSONB. 엔티티에서 @JdbcTypeCode(SqlTypes.JSON)
- updated_at은 DB의 ON UPDATE가 아니라 @UpdateTimestamp로 앱에서 갱신
- 시간대: hibernate.jdbc.time_zone=Asia/Seoul
- analysis_type 등 enum은 @Enumerated(EnumType.STRING)
- H2는 JSONB와 jsonb_typeof CHECK를 지원하지 않을 수 있음. local 프로파일에서 문제가 되면 먼저 알릴 것

## 패키지 구조
- global/ : response(공통 응답 봉투), error(에러 코드·예외 핸들러), config
- facility/ : domain, repository, service, controller, dto (A01~A03, 엔티티, 적재)
- analysis/ : A04 (LLM 호출, 결과 캐시, source_hash·prompt_version)
- transport/ : A05 (교통 접근성, 외부 연동)

## 작업 방식
- 작업 ID는 노션 "백엔드 API 작업 분담"과 동일: B00~B03, A01~A05, T01~T02.
- 한 번에 한 단위만: 엔티티 → 리포지토리 → 서비스 → 컨트롤러 → 테스트.
- 코드를 쓰기 전에 영향받는 엔티티, API, 문서 섹션을 먼저 짚고, 큰 작업은 계획부터 보여준 뒤 승인받을 것.
- API는 07번의 공통 응답 봉투, 에러 코드, camelCase JSON을 그대로 따를 것. DB의 snake_case를 API에 노출하지 말 것.
- 코드는 팀원이 읽고 이어받을 수 있게 단순하게. 과한 추상화 금지, 핵심 로직에만 짧은 주석.
- MVP(02번의 필수) 먼저, 후순위·추가는 MVP가 끝난 뒤.
- 작업이 끝나면 변경 파일, 실행한 테스트, 남은 확인 사항을 요약할 것.

## Git 규칙 (팀 전체 협업 규칙. 노션 "협업 규칙 (Git)" 참고)
- main에서 직접 개발·푸시하지 않음 (최초 세팅 B00만 예외). 작업 전 `git checkout main && git pull origin main`으로 최신화한 뒤 브랜치를 만들 것
- 브랜치: `feature/{작업ID}-{기능명}`, `fix/{수정내용}` (예: feature/B01-entity, fix/error-handler-404)
- 커밋 메시지: `타입: 작업 내용` (예: `feat: facility 엔티티 구현`)
  - 타입: feat, fix, refactor, test, docs, style, chore
  - 한 커밋에는 하나의 의미 있는 작업만. "수정", "작업 완료", "최종" 같은 메시지 금지
- PR 제목: `[Feat] B01 엔티티·리포지토리 구현` (타입 + 작업 ID). 본문에 "작업 내용"과 "확인 사항". 작업 ID 하나당 PR 하나
- PR은 상대 백엔드가 확인한 뒤 main에 merge. B00, B01은 가장 먼저 합칠 것
- 공통 코드(global/, 공통 엔티티, 공통 설정)를 바꿀 때는 먼저 팀에 공유
- API 명세(경로, 요청·응답 형식)를 바꾸는 변경은 프론트와 함께 확인해야 하므로, 임의로 바꾸지 말고 먼저 알릴 것
- 사용자가 요청하기 전에는 git commit, git push를 하지 말 것

## 민감 정보
- 커밋·푸시 금지: OpenAI API Key, DB 비밀번호, 개인 토큰, .env 파일, 기타 Secret
- 키와 비밀번호는 환경 변수로만 읽을 것. 팀 공유용은 값을 뺀 `.env.example`(또는 application-example.yml)로 만들 것
- 실제 비밀 값이 들어가는 파일은 .gitignore에 등록하되, docs/는 무시하지 않음
- 코드, 문서, 로그, 테스트 데이터에 키가 노출되지 않게 할 것

## 응답 형식
- 한국어로 답하되 코드 식별자와 에러 메시지는 원문 그대로 둘 것.
- 결론을 먼저, 근거는 뒤에. 결정이 필요한 사항은 마지막에 "확인 필요"로 모아 묻기.