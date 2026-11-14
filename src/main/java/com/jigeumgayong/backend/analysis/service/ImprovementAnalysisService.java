package com.jigeumgayong.backend.analysis.service;

import com.jigeumgayong.backend.analysis.config.AnalysisConfig;
import com.jigeumgayong.backend.analysis.domain.AiAnalysis;
import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisResponse;
import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisResponse.CacheInfo;
import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisResponse.FacilityBase;
import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisResponse.ImprovementItem;
import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisResponse.IntegratedSuggestion;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Accessibility;
import com.jigeumgayong.backend.transport.dto.TransportAccessibilityResponse.Status;
import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisResponse.WeakTimeAnalysis;
import com.jigeumgayong.backend.analysis.llm.LlmClient;
import com.jigeumgayong.backend.analysis.llm.LlmProperties;
import com.jigeumgayong.backend.analysis.ratelimit.ClientKey;
import com.jigeumgayong.backend.analysis.ratelimit.LlmRateLimiter;
import com.jigeumgayong.backend.analysis.ratelimit.LlmRateLimiter.Decision;
import com.jigeumgayong.backend.analysis.service.ImprovementObservations.Observation;
import com.jigeumgayong.backend.analysis.service.ImprovementPayload.Context;
import com.jigeumgayong.backend.analysis.service.ImprovementPayload.SpaceProposal;
import com.jigeumgayong.backend.analysis.service.ImprovementPrompt.Facts;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Coordinates;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.DaySchedule;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.Candidate;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.CandidateResult;
import com.jigeumgayong.backend.facility.service.PeerHoursComparison;
import com.jigeumgayong.backend.facility.service.SearchCriteriaNormalizer;
import com.jigeumgayong.backend.facility.service.SearchCriteriaNormalizer.Normalized;
import com.jigeumgayong.backend.facility.service.TimeText;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.global.error.ErrorDetail;
import com.jigeumgayong.backend.global.response.Meta;
import com.jigeumgayong.backend.global.response.Warning;
import com.jigeumgayong.backend.global.response.WarningCode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * A04 AI 개선안. 대상은 요청의 facilityId 한 시설이며, 희망 시간에 이용 가능한지와 관계없이 개선안을 만든다.
 * 흐름: 공간·관찰 계산 → 시설 단위 캐시 조회(HIT이면 LLM 미호출) → MISS면 시설당 LLM 1회 생성
 * → 검증 통과분만 저장 → 공간별 항목으로 응답 조립. LLM·캐시 실패는 200 부분 응답으로 처리한다.
 * 관찰(부족한 점)이 하나도 없으면 LLM을 호출하지 않고 고정 문구를 돌려준다.
 */
@Slf4j
@Service
public class ImprovementAnalysisService {

    static final String FAILED_REASON = "AI 제안을 생성하지 못했습니다.";
    static final String NO_ISSUE_TEXT = "현재 확인된 개선 사항이 없습니다.";

    private static final String HIT = "HIT";
    private static final String CREATED = "CREATED";
    private static final String NOT_STORED = "NOT_STORED";
    private static final String UNAVAILABLE = "UNAVAILABLE";
    private static final Pattern POSITIVE_INT = Pattern.compile("^[1-9][0-9]*$");

    /** 컨트롤러가 ApiResponse로 감쌀 결과 */
    /** @param retryAfterSeconds AI 호출 한도에 걸린 항목이 있으면 다시 시도할 수 있기까지 남은 초(Retry-After 헤더용), 없으면 null */
    public record AnalysisResult(ImprovementAnalysisResponse data, List<Warning> warnings, Long retryAfterSeconds) {

        public AnalysisResult(ImprovementAnalysisResponse data, List<Warning> warnings) {
            this(data, warnings, null);
        }
    }

    private final SearchCriteriaNormalizer normalizer;
    private final ImprovementCandidateFinder finder;
    private final ImprovementCache cache;
    private final LlmClient llm;
    private final LlmProperties props;
    private final LlmRateLimiter limiter;
    private final ExecutorService executor;
    /** app.analysis.transport-link.enabled: false이면 모든 항목의 transport가 NOT_ENABLED이고 정류장 수를 LLM에 보내지 않는다 */
    private final boolean transportLinkEnabled;
    /** app.analysis.general-suggestion.enabled: true이면 부족한 점이 없는 시설도 일반 보완 제안을 LLM으로 만든다(기본 false) */
    private final boolean generalSuggestionEnabled;
    private final PeerHoursComparison peerComparison;
    /** app.analysis.peer-comparison.enabled: false이면 같은 구·같은 유형 시설과의 운영시간 비교 관찰을 만들지 않는다(기본 true) */
    private final boolean peerComparisonEnabled;
    private final FloatingPopulation flowPopulation;
    /** app.analysis.flow-statistics.enabled: false이면 경기도 유동인구 통계 비교 관찰(FLOW_*)을 만들지 않는다(기본 true) */
    private final boolean flowStatisticsEnabled;

    public ImprovementAnalysisService(SearchCriteriaNormalizer normalizer, ImprovementCandidateFinder finder,
                                      ImprovementCache cache, LlmClient llm, LlmProperties props,
                                      LlmRateLimiter limiter,
                                      @Qualifier(AnalysisConfig.LLM_EXECUTOR) ExecutorService executor,
                                      PeerHoursComparison peerComparison, FloatingPopulation flowPopulation,
                                      @Value("${app.analysis.transport-link.enabled:true}") boolean transportLinkEnabled,
                                      @Value("${app.analysis.general-suggestion.enabled:false}") boolean generalSuggestionEnabled,
                                      @Value("${app.analysis.peer-comparison.enabled:true}") boolean peerComparisonEnabled,
                                      @Value("${app.analysis.flow-statistics.enabled:true}") boolean flowStatisticsEnabled) {
        this.peerComparison = peerComparison;
        this.peerComparisonEnabled = peerComparisonEnabled;
        this.flowPopulation = flowPopulation;
        this.flowStatisticsEnabled = flowStatisticsEnabled;
        this.normalizer = normalizer;
        this.finder = finder;
        this.cache = cache;
        this.llm = llm;
        this.props = props;
        this.limiter = limiter;
        this.executor = executor;
        this.transportLinkEnabled = transportLinkEnabled;
        this.generalSuggestionEnabled = generalSuggestionEnabled;
    }

    /** 시설 한 곳의 분석 상태 */
    private static final class Prepared {
        final long facilityId;
        final List<Candidate> spaces;
        final Accessibility transport;
        final Context ctx;
        final String factsJson;
        final String sourceHash;
        final String scopeKey;
        final String paramsKey;
        AiAnalysis existing;
        boolean readFailed;
        Outcome outcome; // HIT이면 미리 채움
        volatile boolean llmCalled; // 워커 스레드가 LLM을 실제로 호출했는지 (로그용)
        volatile long retryAfterSeconds; // AI 호출 한도에 걸렸으면 남은 초, 아니면 0

        Prepared(long facilityId, List<Candidate> spaces, Accessibility transport, Context ctx, String factsJson,
                 String sourceHash, String scopeKey, String paramsKey) {
            this.facilityId = facilityId;
            this.spaces = spaces;
            this.transport = transport;
            this.ctx = ctx;
            this.factsJson = factsJson;
            this.sourceHash = sourceHash;
            this.scopeKey = scopeKey;
            this.paramsKey = paramsKey;
        }

        String lockKey() {
            return scopeKey + "#" + paramsKey;
        }
    }

    private record Outcome(String cacheStatus, ImprovementPayload payload, Long analysisId, LocalDateTime time,
                           String failReason, List<Warning> warnings) {

        static Outcome unavailable(String reason, List<Warning> warnings) {
            return new Outcome(UNAVAILABLE, null, null, null, reason, warnings);
        }
    }

    /** 요청자 식별 정보가 없는 호출(내부·테스트). AI 호출 한도는 익명 한 사람으로 센다. */
    public AnalysisResult analyze(String facilityIdText, String keyword, String district, List<Integer> typeIds,
                                  int dayOfWeek, String startText, String endText) {
        return analyze(facilityIdText, keyword, district, typeIds, dayOfWeek, startText, endText, ClientKey.ANONYMOUS);
    }

    /**
     * @param facilityIdText 필수. 후보는 이 시설의 공간뿐이다(keyword·district·typeIds는 후보 필터에 쓰지 않음).
     *                       criteria 여섯 필드는 그대로 검증하고 정규화한 값을 응답 data.criteria로 돌려준다.
     * @param client         AI 호출 한도를 셀 요청자(X-Client-Id·IP)
     */
    public AnalysisResult analyze(String facilityIdText, String keyword, String district, List<Integer> typeIds,
                                  int dayOfWeek, String startText, String endText, ClientKey client) {
        long deadline = System.nanoTime() + props.requestDeadline().toNanos();

        // 1) 요청 검증·정규화(A03과 같은 순서: facilityId 형식 → 조건), 2) 후보 계산 (DB 실패는 503으로 전파)
        long facilityId = parseFacilityId(facilityIdText);
        Normalized n;
        try {
            n = normalizer.normalize(keyword, district, typeIds, dayOfWeek, startText, endText);
        } catch (BusinessException e) {
            // 본문 검증 오류(criteria.dayOfWeek)와 맞추려고 A04에서만 field에 criteria. 접두사를 붙인다
            throw new BusinessException(e.getErrorCode(), e.getMessage(), e.getDetails().stream()
                    .map(d -> new ErrorDetail("criteria." + d.field(), d.reason())).toList());
        }
        CandidateResult found = finder.findForFacility(facilityId, n, dayOfWeek);

        Map<String, Warning> warnings = new LinkedHashMap<>();
        addWarning(warnings, Warning.of(WarningCode.WEEKLY_SCHEDULE_ONLY,
                "정규 운영시간 기준입니다. 공휴일·임시휴관과 예약 여부는 별도 확인하세요."));

        // 3) 공간이 하나도 없으면 분석할 것이 없다
        List<Candidate> spaces = found.candidates();
        if (spaces.isEmpty()) {
            return new AnalysisResult(new ImprovementAnalysisResponse(n.criteria(), 0, 0,
                    found.unknownSpaceCount(), List.of(), ImprovementAnalysisResponse.DISCLAIMER),
                    List.copyOf(warnings.values()));
        }

        // 4) 교통(이미 로드된 Facility.busStopCount)과 관찰(부족한 점)을 계산한다
        Accessibility transport = ImprovementTransport.of(spaces.get(0), transportLinkEnabled);
        Map<SpaceType, List<DaySchedule>> weekly = finder.weeklyHours(facilityId);
        List<Observation> observations = ImprovementObservations.build(spaces, weekly, transport);
        if (peerComparisonEnabled || flowStatisticsEnabled) {
            observations = withComparisonObservations(observations, spaces, weekly, n.startMin(), n.endMin(),
                    dayOfWeek);
        }
        String fid = String.valueOf(facilityId);
        addTransportWarning(warnings, transport, fid);

        // 5) 관찰이 없으면 LLM 없이 고정 문구. 단, 일반 보완 제안을 켠 경우에는 대체 관찰 하나로 LLM 흐름을 그대로 탄다
        if (observations.isEmpty() && generalSuggestionEnabled) {
            observations = List.of(ImprovementObservations.generalReview());
        }
        if (observations.isEmpty()) {
            List<ImprovementItem> items = spaces.stream().map(c -> noIssueItem(c, transport)).toList();
            return new AnalysisResult(new ImprovementAnalysisResponse(n.criteria(), items.size(), 1,
                    found.unknownSpaceCount(), items, ImprovementAnalysisResponse.DISCLAIMER),
                    List.copyOf(warnings.values()));
        }

        // 6) 시설 단위 캐시 조회. HIT은 LLM을 호출하지 않는다
        Facts facts = ImprovementPrompt.facts(spaces, transport, observations);
        String factsJson = ImprovementPrompt.factsJson(facts);
        Set<String> observationIds = observations.stream().map(Observation::id).collect(Collectors.toSet());
        Prepared p = new Prepared(facilityId, spaces, transport, new Context(spaces, observationIds, factsJson),
                factsJson, ImprovementPrompt.sourceHash(factsJson), ImprovementCache.scopeKey(facilityId),
                ImprovementCache.paramsKey(dayOfWeek, n.startMin(), n.endMin()));
        lookup(p);
        if (p.readFailed) {
            addWarning(warnings, Warning.of(WarningCode.CACHE_READ_FAILED,
                    "AI 캐시를 조회하지 못해 새로 생성을 시도했습니다.", fid));
        }

        // 7) 생성(시설당 1회). 요청 전체 제한 시간을 넘으면 UNAVAILABLE
        if (p.outcome == null) {
            Future<Outcome> future = executor.submit(() -> generate(p, deadline, client));
            p.outcome = await(future, p, deadline);
        }

        // 8) 응답 조립: 공간마다 항목 하나, 시설 종합 문장은 항목마다 같다
        p.outcome.warnings().forEach(w -> addWarning(warnings, w));
        // 키·요청/응답 본문·헤더는 남기지 않는다. 시설 식별자와 처리 결과만 기록한다
        log.info("A04 분석 facilityId={} cache={} llmCalled={}", facilityId, p.outcome.cacheStatus(), p.llmCalled);
        List<ImprovementItem> items = spaces.stream().map(c -> toItem(p, c)).toList();
        return new AnalysisResult(new ImprovementAnalysisResponse(n.criteria(), items.size(), 1,
                found.unknownSpaceCount(), items, ImprovementAnalysisResponse.DISCLAIMER),
                List.copyOf(warnings.values()), p.retryAfterSeconds > 0 ? p.retryAfterSeconds : null);
    }

    /** A05 컨트롤러와 같은 코드·문구. NOT_ENABLED는 경고하지 않는다. */
    private static void addTransportWarning(Map<String, Warning> warnings, Accessibility transport, String fid) {
        if (transport.status() == Status.NO_DATA) {
            addWarning(warnings, Warning.of(WarningCode.TRANSPORT_DATA_UNAVAILABLE,
                    "교통 정보를 확인할 수 없습니다.", fid));
        } else if (transport.status() == Status.AVAILABLE) {
            addWarning(warnings, Warning.of(WarningCode.GRADE_RULE_NOT_DEFINED,
                    "정류장 수는 제공하지만 등급 기준은 미정입니다.", fid));
        }
    }

    /** 캐시 조회. 조회 실패는 MISS로 보고 생성을 시도한다(CACHE_READ_FAILED 경고). */
    private void lookup(Prepared p) {
        try {
            p.existing = cache.find(p.scopeKey, p.paramsKey).orElse(null);
        } catch (DataAccessException e) {
            log.warn("AI 캐시 조회 실패: {}", e.getClass().getSimpleName());
            p.readFailed = true;
        }
        Optional<ImprovementPayload> hit = validHit(p.existing, p);
        if (hit.isPresent()) {
            p.outcome = hitOutcome(p.existing, hit.get());
        }
    }

    /** 키·hash·prompt_version이 현재 값과 같고 payload 재검증을 통과해야 유효한 HIT */
    private Optional<ImprovementPayload> validHit(AiAnalysis row, Prepared p) {
        if (row == null || !p.sourceHash.equals(row.getSourceHash())
                || !ImprovementPrompt.PROMPT_VERSION.equals(row.getPromptVersion())) {
            return Optional.empty();
        }
        try {
            return Optional.of(ImprovementPayload.fromStored(row.getPayload(), p.ctx));
        } catch (InvalidPayloadException e) {
            log.warn("저장된 AI payload 검증 실패(재생성): {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static Outcome hitOutcome(AiAnalysis row, ImprovementPayload payload) {
        return new Outcome(HIT, payload, row.getAnalysisId(), row.getUpdatedAt(), null, List.of());
    }

    private Outcome await(Future<Outcome> future, Prepared p, long deadline) {
        long remaining = Math.max(0, deadline - System.nanoTime());
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("AI 개선안 요청 제한 시간 초과");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
        } catch (ExecutionException e) {
            log.warn("AI 개선안 생성 중 예기치 못한 오류: {}", e.getCause() == null ? "" : e.getCause().getClass());
        }
        return Outcome.unavailable(FAILED_REASON, List.of(llmWarning(p, FAILED_REASON)));
    }

    /** 워커 스레드: 키별 락 → 캐시 재확인 → LLM 호출 → 검증 → 저장 */
    private Outcome generate(Prepared p, long deadline, ClientKey client) {
        if (System.nanoTime() >= deadline) {
            return Outcome.unavailable(FAILED_REASON, List.of(llmWarning(p, FAILED_REASON)));
        }
        return cache.withLock(p.lockKey(), () -> {
            List<Warning> warnings = new ArrayList<>();
            AiAnalysis current = p.existing;
            boolean readFailed = p.readFailed;
            // 락을 기다리는 사이 다른 요청이 저장했을 수 있으니 한 번 더 확인한다(중복 과금 방지)
            try {
                current = cache.find(p.scopeKey, p.paramsKey).orElse(null);
                readFailed = false;
                Optional<ImprovementPayload> hit = validHit(current, p);
                if (hit.isPresent()) {
                    return hitOutcome(current, hit.get());
                }
            } catch (DataAccessException e) {
                readFailed = true;
            }
            if (readFailed) {
                warnings.add(Warning.of(WarningCode.CACHE_READ_FAILED,
                        "AI 캐시를 조회하지 못해 새로 생성했습니다.", facilityId(p)));
            }
            if (System.nanoTime() >= deadline) {
                warnings.add(llmWarning(p, FAILED_REASON));
                return Outcome.unavailable(FAILED_REASON, warnings);
            }

            // OpenAI를 호출하기 직전에 한도를 확인한다. 캐시 HIT은 위에서 이미 반환했으므로 소모하지 않는다.
            Decision decision = limiter.tryAcquire(client);
            if (!decision.allowed()) {
                p.retryAfterSeconds = decision.retryAfterSeconds();
                String reason = rateLimitReason(decision.retryAfterSeconds());
                warnings.add(Warning.of(WarningCode.RATE_LIMITED, reason, facilityId(p)));
                return Outcome.unavailable(reason, warnings);
            }

            ImprovementPayload payload;
            try {
                p.llmCalled = true;
                String text = llm.generateJson(ImprovementPrompt.INSTRUCTIONS, p.factsJson,
                        ImprovementPrompt.SCHEMA_NAME, ImprovementPrompt.outputSchema());
                payload = ImprovementPayload.fromLlm(text, p.ctx);
            } catch (RuntimeException e) {
                // 키 없음·타임아웃·거부·JSON/검증 실패. 검증 실패 결과는 저장하지 않는다.
                log.warn("AI 개선안 생성 실패: {}", e.getMessage());
                warnings.add(llmWarning(p, FAILED_REASON));
                return Outcome.unavailable(FAILED_REASON, warnings);
            }
            return store(p, current, readFailed, payload, warnings);
        });
    }

    private Outcome store(Prepared p, AiAnalysis current, boolean readFailed, ImprovementPayload payload,
                          List<Warning> warnings) {
        try {
            // 시설 단위 행이라 space_type은 비운다
            AiAnalysis saved = cache.save(readFailed ? null : current, p.facilityId, null,
                    p.paramsKey, payload.toJson(), p.sourceHash, ImprovementPrompt.PROMPT_VERSION,
                    llm.modelName());
            if (current != null && !readFailed) {
                warnings.add(Warning.of(WarningCode.CACHE_INVALIDATED,
                        "운영 정보나 프롬프트가 바뀌어 저장된 AI 제안을 다시 생성했습니다.", facilityId(p)));
            }
            return new Outcome(CREATED, payload, saved.getAnalysisId(), saved.getUpdatedAt(), null, warnings);
        } catch (DataIntegrityViolationException e) {
            // 동시 INSERT(UNIQUE 충돌): 500이 아니라 저장된 행을 다시 읽어 쓴다
            try {
                AiAnalysis other = cache.find(p.scopeKey, p.paramsKey).orElse(null);
                Optional<ImprovementPayload> hit = validHit(other, p);
                if (hit.isPresent()) {
                    return new Outcome(HIT, hit.get(), other.getAnalysisId(), other.getUpdatedAt(), null, warnings);
                }
            } catch (DataAccessException ignored) {
                // 아래 NOT_STORED로 처리
            }
        } catch (DataAccessException e) {
            log.warn("AI 캐시 저장 실패: {}", e.getClass().getSimpleName());
        }
        warnings.add(Warning.of(WarningCode.CACHE_WRITE_FAILED,
                "AI 제안은 생성했지만 캐시에 저장하지 못했습니다.", facilityId(p)));
        return new Outcome(NOT_STORED, payload, null, null, null, warnings);
    }

    // --- 응답 조립 ---

    private ImprovementItem toItem(Prepared p, Candidate c) {
        Outcome o = p.outcome;
        FacilityBase base = facilityBase(c);
        String currentClosing = c.currentCloseMin() == null ? null : TimeText.format(c.currentCloseMin());
        Accessibility transport = p.transport;
        WeakTimeAnalysis weak = new WeakTimeAnalysis("NOT_SUPPORTED", List.of());

        if (o.payload() == null) {
            return new ImprovementItem(base, c.spaceType().name(), c.spaceLabel(), c.selectedSchedule(),
                    c.availability(), currentClosing, null, "UNAVAILABLE", o.failReason(), List.of(),
                    new IntegratedSuggestion("UNAVAILABLE", null), transport, weak, null,
                    new CacheInfo(UNAVAILABLE, null), limitations(c, transport));
        }
        ImprovementPayload pl = o.payload();
        SpaceProposal sp = pl.proposalOf(c.spaceType().name());
        List<String> alternatives = sp == null ? List.of()
                : sp.alternatives().stream().map(ImprovementPayload.Alternative::text).toList();
        String proposed = sp == null || sp.proposedCloseMin() == null ? null : TimeText.format(sp.proposedCloseMin());
        boolean stored = o.analysisId() != null;
        return new ImprovementItem(base, c.spaceType().name(), c.spaceLabel(), c.selectedSchedule(),
                c.availability(), currentClosing, proposed, "GENERATED", reasonFor(c), alternatives,
                new IntegratedSuggestion(suggestionStatus(pl, transport), pl.integratedSuggestion()), transport, weak,
                stored ? String.valueOf(o.analysisId()) : null,
                new CacheInfo(o.cacheStatus(), stored && o.time() != null
                        ? o.time().atZone(Meta.ZONE).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS) : null),
                limitations(c, transport));
    }

    /** 관찰이 없을 때의 항목. LLM을 부르지 않았으므로 제안은 비고 고정 문구만 있다(저장하지 않음). */
    private ImprovementItem noIssueItem(Candidate c, Accessibility transport) {
        String currentClosing = c.currentCloseMin() == null ? null : TimeText.format(c.currentCloseMin());
        return new ImprovementItem(facilityBase(c), c.spaceType().name(), c.spaceLabel(), c.selectedSchedule(),
                c.availability(), currentClosing, null, "UNAVAILABLE", NO_ISSUE_TEXT, List.of(),
                new IntegratedSuggestion("UNAVAILABLE", NO_ISSUE_TEXT), transport,
                new WeakTimeAnalysis("NOT_SUPPORTED", List.of()), null, new CacheInfo(UNAVAILABLE, null),
                limitations(c, transport));
    }

    private static FacilityBase facilityBase(Candidate c) {
        var f = c.facility();
        return new FacilityBase(String.valueOf(f.getFacilityId()), f.getType().getTypeId(),
                f.getType().getName(), f.getDistrict(), f.getAccessType().name(), f.getName(), f.getAddress(),
                f.getType().getDefaultImage(), new Coordinates(f.getLatitude(), f.getLongitude()));
    }

    /**
     * 06 IntegratedSuggestion: 교통 근거(정류장 수)를 문장에 반영했으면 COMPLETE, 아니면 PARTIAL. 응답 시 판정하고 저장하지 않는다.
     * 문장 속 숫자가 현재 정류장 수와 같은지는 ImprovementPayload 검증(HIT도 현재 facts로 재검증)이 보장한다.
     */
    private static String suggestionStatus(ImprovementPayload pl, Accessibility transport) {
        return transport.status() == Status.AVAILABLE && pl.suggestionMentionsStopCount() ? "COMPLETE" : "PARTIAL";
    }

    /** 서버가 사실(구간 차이, 예약 여부)로 정하는 설명. LLM 문장이 아니다. */
    private static String reasonFor(Candidate c) {
        String range = TimeText.format(c.startMin()) + "–" + TimeText.format(c.endMin());
        String reason;
        if ("UNKNOWN".equals(c.availability().status())) {
            reason = c.spaceLabel() + "은(는) 해당 요일 운영시간을 확인할 수 없습니다.";
        } else if ("AVAILABLE".equals(c.availability().status())) {
            reason = c.spaceLabel() + "은(는) 희망 시간(" + range + ")에 이용할 수 있습니다. 운영 현황을 바탕으로 한 보완 제안입니다.";
        } else if (c.currentCloseMin() == null) {
            reason = c.spaceLabel() + "은(는) 해당 요일 정기 휴관이라 희망 시간(" + range + ")에 이용할 수 없습니다.";
        } else if (c.extendable()) {
            reason = c.spaceLabel() + "의 운영 종료(" + TimeText.format(c.currentCloseMin())
                    + ")가 희망 이용 시간(" + range + ")보다 빠릅니다.";
        } else {
            reason = c.spaceLabel() + "의 운영시간이 희망 이용 시간(" + range + ") 전체를 포함하지 않습니다.";
        }
        if (c.requiresReservation()) {
            reason += " 예약이 필요한 시설이며, 예약 가능 여부는 판단하지 않았습니다.";
        }
        return reason;
    }

    private static List<String> limitations(Candidate c, Accessibility transport) {
        String transportLimit = transport.status() == Status.AVAILABLE
                ? "정류장 개수 외 시간대별 운행자료가 없습니다."
                : "교통·운행 자료를 반영하지 않았습니다.";
        List<String> list = new ArrayList<>(List.of(
                "주간 정규시간 기준이며 공휴일·주차별 휴관을 판정하지 않습니다.",
                transportLimit,
                "운영 인력·예산·시설 규정을 검토한 확정 정책이 아닙니다."));
        if (c.requiresReservation()) {
            list.add("예약제 시설입니다. 운영시간만 비교했으며 예약 가능 여부는 판단하지 않습니다.");
        }
        return list;
    }

    /**
     * 같은 행정구·같은 유형 시설과의 운영시간 비교(PEER_*)와 경기도 유동인구 통계 비교(FLOW_*) 관찰을
     * 기존 관찰 뒤에 붙인다(id는 이어서 매긴다). 비교 조회가 실패해도 비교만 빼고 분석을 이어 간다.
     */
    private List<Observation> withComparisonObservations(List<Observation> base, List<Candidate> spaces,
                                                         Map<SpaceType, List<DaySchedule>> weekly, int startMin,
                                                         int endMin, int dayOfWeek) {
        try {
            boolean ownOpen = spaces.stream().anyMatch(c -> "AVAILABLE".equals(c.availability().status()));
            Integer ownClose = spaces.stream().map(Candidate::currentCloseMin).filter(java.util.Objects::nonNull)
                    .max(Integer::compare).orElse(null);
            Facility facility = spaces.get(0).facility();
            PeerHoursComparison.Result peers = peerComparison.compare(facility, dayOfWeek, startMin, endMin);
            List<Observation> extra = ImprovementObservations.comparisonObservations(peerComparisonEnabled,
                    flowStatisticsEnabled, peers, flowPopulation, facility.getDistrict(), ownClose, ownOpen, weekly,
                    dayOfWeek, startMin, endMin);
            if (extra.isEmpty()) {
                return base;
            }
            List<Observation> all = new ArrayList<>(base);
            for (Observation o : extra) {
                all.add(new Observation("O" + (all.size() + 1), o.type(), o.spaceType(), o.detail()));
            }
            return all;
        } catch (RuntimeException e) {
            log.warn("A04 비교 관찰(동종 시설·유동인구) 계산에 실패해 비교 없이 분석합니다: {}", e.getClass().getSimpleName());
            return base;
        }
    }

    private static String rateLimitReason(long retryAfterSeconds) {
        return "AI 호출 한도를 초과했습니다. " + retryAfterSeconds + "초 후 다시 시도해 주세요.";
    }

    /**
     * 필수이고, 10진수 양수 문자열이며 Long 범위 안이어야 한다(A03 parseFacilityId와 같은 규칙). 아니면 400 field=facilityId.
     * 누락은 컨트롤러 @NotNull이 먼저 막고, 여기서는 서비스를 직접 부르는 경우를 대비한다.
     */
    private static long parseFacilityId(String text) {
        if (text == null) {
            throw invalidFacilityId(ErrorDetail.REQUIRED);
        }
        if (!POSITIVE_INT.matcher(text).matches()) {
            throw invalidFacilityId(ErrorDetail.INVALID_FORMAT);
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw invalidFacilityId(ErrorDetail.OUT_OF_RANGE);
        }
    }

    private static BusinessException invalidFacilityId(String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(),
                List.of(new ErrorDetail("facilityId", reason)));
    }

    private static Warning llmWarning(Prepared p, String reason) {
        return Warning.of(WarningCode.LLM_UNAVAILABLE, reason, facilityId(p));
    }

    private static String facilityId(Prepared p) {
        return String.valueOf(p.facilityId);
    }

    /** (code, facilityId)가 같은 경고는 한 번만 */
    private static void addWarning(Map<String, Warning> warnings, Warning w) {
        warnings.putIfAbsent(w.code() + "|" + w.facilityId(), w);
    }
}
