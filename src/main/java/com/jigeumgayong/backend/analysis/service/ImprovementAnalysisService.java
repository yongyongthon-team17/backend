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
import com.jigeumgayong.backend.analysis.service.ImprovementPrompt.Facts;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Coordinates;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.Candidate;
import com.jigeumgayong.backend.facility.service.ImprovementCandidateFinder.CandidateResult;
import com.jigeumgayong.backend.facility.service.SearchCriteriaNormalizer;
import com.jigeumgayong.backend.facility.service.SearchCriteriaNormalizer.Normalized;
import com.jigeumgayong.backend.facility.service.TimeText;
import com.jigeumgayong.backend.global.error.BusinessException;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * A04 공간별 불일치 분석 및 AI 개선안.
 * 흐름: 후보 계산 → 후보별 캐시 조회(HIT이면 LLM 미호출) → MISS 중 앞에서 N개만 LLM 생성(병렬)
 * → 검증 통과분만 저장 → 응답 조립. LLM·캐시 일부 실패는 200 부분 응답으로 처리한다.
 */
@Slf4j
@Service
public class ImprovementAnalysisService {

    static final String LIMIT_REASON = "이번 요청의 생성 한도 초과";
    static final String FAILED_REASON = "AI 제안을 생성하지 못했습니다.";

    private static final String HIT = "HIT";
    private static final String CREATED = "CREATED";
    private static final String NOT_STORED = "NOT_STORED";
    private static final String UNAVAILABLE = "UNAVAILABLE";

    /** 컨트롤러가 ApiResponse로 감쌀 결과 */
    public record AnalysisResult(ImprovementAnalysisResponse data, List<Warning> warnings) {
    }

    private final SearchCriteriaNormalizer normalizer;
    private final ImprovementCandidateFinder finder;
    private final ImprovementCache cache;
    private final LlmClient llm;
    private final LlmProperties props;
    private final ExecutorService executor;
    /** app.analysis.transport-link.enabled: false이면 모든 항목의 transport가 NOT_ENABLED이고 정류장 수를 LLM에 보내지 않는다 */
    private final boolean transportLinkEnabled;

    public ImprovementAnalysisService(SearchCriteriaNormalizer normalizer, ImprovementCandidateFinder finder,
                                      ImprovementCache cache, LlmClient llm, LlmProperties props,
                                      @Qualifier(AnalysisConfig.LLM_EXECUTOR) ExecutorService executor,
                                      @Value("${app.analysis.transport-link.enabled:true}") boolean transportLinkEnabled) {
        this.normalizer = normalizer;
        this.finder = finder;
        this.cache = cache;
        this.llm = llm;
        this.props = props;
        this.executor = executor;
        this.transportLinkEnabled = transportLinkEnabled;
    }

    /** 후보 한 건의 처리 상태 */
    private static final class Prepared {
        final Candidate candidate;
        final Accessibility transport;
        final String factsJson;
        final String sourceHash;
        final String scopeKey;
        final String paramsKey;
        AiAnalysis existing;
        boolean readFailed;
        Outcome outcome; // HIT 또는 한도 초과처럼 이미 정해졌으면 채움
        volatile boolean llmCalled; // 워커 스레드가 LLM을 실제로 호출했는지 (로그용)

        Prepared(Candidate candidate, Accessibility transport, String factsJson, String sourceHash, String scopeKey,
                 String paramsKey) {
            this.candidate = candidate;
            this.transport = transport;
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

    public AnalysisResult analyze(String keyword, String district, List<Integer> typeIds, int dayOfWeek,
                                  String startText, String endText) {
        long deadline = System.nanoTime() + props.requestDeadline().toNanos();

        // 1) 요청 검증·정규화, 2) 후보 계산 (DB 실패는 503으로 전파)
        Normalized n;
        try {
            n = normalizer.normalize(keyword, district, typeIds, dayOfWeek, startText, endText);
        } catch (BusinessException e) {
            // 본문 검증 오류(criteria.dayOfWeek)와 맞추려고 A04에서만 field에 criteria. 접두사를 붙인다
            throw new BusinessException(e.getErrorCode(), e.getMessage(), e.getDetails().stream()
                    .map(d -> new ErrorDetail("criteria." + d.field(), d.reason())).toList());
        }
        CandidateResult found = finder.find(n, dayOfWeek);

        Map<String, Warning> warnings = new LinkedHashMap<>();
        addWarning(warnings, Warning.of(WarningCode.WEEKLY_SCHEDULE_ONLY,
                "정규 운영시간 기준입니다. 공휴일·임시휴관과 예약 여부는 별도 확인하세요."));
        if (found.unknownSpaceCount() > 0) {
            addWarning(warnings, Warning.of(WarningCode.UNKNOWN_HOURS_EXCLUDED,
                    "운영시간을 확인할 수 없는 공간 " + found.unknownSpaceCount() + "곳은 분석에서 제외했습니다."));
        }

        // 3) 후보가 없으면 LLM 호출 없이 반환
        List<Candidate> candidates = found.candidates();
        if (candidates.isEmpty()) {
            return new AnalysisResult(new ImprovementAnalysisResponse(n.criteria(), 0, 0,
                    found.unknownSpaceCount(), List.of(), ImprovementAnalysisResponse.DISCLAIMER),
                    List.copyOf(warnings.values()));
        }

        // 4) 캐시 조회: 후보 순서(facilityId, spaceType) 그대로. HIT은 LLM을 호출하지 않는다
        List<Prepared> prepared = new ArrayList<>();
        for (Candidate c : candidates) {
            // 이미 로드된 Facility.busStopCount를 읽는다(추가 쿼리 없음)
            Accessibility transport = ImprovementTransport.of(c, transportLinkEnabled);
            Facts facts = ImprovementPrompt.facts(c, transport);
            String factsJson = ImprovementPrompt.factsJson(facts);
            long fid = c.facility().getFacilityId();
            Prepared p = new Prepared(c, transport, factsJson, ImprovementPrompt.sourceHash(factsJson),
                    ImprovementCache.scopeKey(fid),
                    ImprovementCache.paramsKey(c.spaceType(), c.dayOfWeek(), c.startMin(), c.endMin()));
            lookup(p);
            if (p.readFailed) {
                addWarning(warnings, Warning.of(WarningCode.CACHE_READ_FAILED,
                        "AI 캐시를 조회하지 못해 새로 생성을 시도했습니다.", facilityId(p)));
            }
            prepared.add(p);
        }

        // 5) MISS 중 앞에서 maxGenerationsPerRequest개만 생성 대상. 나머지는 한도 초과(UNAVAILABLE)
        List<Prepared> toGenerate = new ArrayList<>();
        for (Prepared p : prepared) {
            if (p.outcome != null) {
                continue;
            }
            if (toGenerate.size() < props.maxGenerationsPerRequest()) {
                toGenerate.add(p);
            } else {
                p.outcome = Outcome.unavailable(LIMIT_REASON, List.of(llmWarning(p, LIMIT_REASON)));
            }
        }

        // 6) 생성(병렬). 요청 전체 제한 시간을 넘으면 남은 항목은 UNAVAILABLE
        List<Future<Outcome>> futures = new ArrayList<>();
        for (Prepared p : toGenerate) {
            futures.add(executor.submit(() -> generate(p, deadline)));
        }
        for (int i = 0; i < toGenerate.size(); i++) {
            toGenerate.get(i).outcome = await(futures.get(i), toGenerate.get(i), deadline);
        }

        // 7) 응답 조립
        List<ImprovementItem> items = new ArrayList<>();
        for (Prepared p : prepared) {
            p.outcome.warnings().forEach(w -> addWarning(warnings, w));
            // A05 컨트롤러와 같은 코드·문구. NOT_ENABLED는 경고하지 않는다. (code, facilityId)당 한 번만 담긴다
            if (p.transport.status() == Status.NO_DATA) {
                addWarning(warnings, Warning.of(WarningCode.TRANSPORT_DATA_UNAVAILABLE,
                        "교통 정보를 확인할 수 없습니다.", facilityId(p)));
            } else if (p.transport.status() == Status.AVAILABLE) {
                addWarning(warnings, Warning.of(WarningCode.GRADE_RULE_NOT_DEFINED,
                        "정류장 수는 제공하지만 등급 기준은 미정입니다.", facilityId(p)));
            }
            // 키·요청/응답 본문·헤더는 남기지 않는다. 시설·공간 식별자와 처리 결과만 기록한다
            log.info("A04 분석 facilityId={} space={} cache={} llmCalled={}", p.candidate.facility().getFacilityId(),
                    p.candidate.spaceType(), p.outcome.cacheStatus(), p.llmCalled);
            items.add(toItem(p));
        }
        int facilityCount = (int) candidates.stream().map(c -> c.facility().getFacilityId()).distinct().count();
        return new AnalysisResult(new ImprovementAnalysisResponse(n.criteria(), items.size(), facilityCount,
                found.unknownSpaceCount(), items, ImprovementAnalysisResponse.DISCLAIMER),
                List.copyOf(warnings.values()));
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
            return Optional.of(ImprovementPayload.fromStored(row.getPayload(), p.candidate, p.factsJson));
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
    private Outcome generate(Prepared p, long deadline) {
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

            ImprovementPayload payload;
            try {
                p.llmCalled = true;
                String text = llm.generateJson(ImprovementPrompt.INSTRUCTIONS, p.factsJson,
                        ImprovementPrompt.SCHEMA_NAME, ImprovementPrompt.outputSchema());
                payload = ImprovementPayload.fromLlm(text, p.candidate, p.factsJson);
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
        Candidate c = p.candidate;
        try {
            AiAnalysis saved = cache.save(readFailed ? null : current, c.facility().getFacilityId(), c.spaceType(),
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

    private ImprovementItem toItem(Prepared p) {
        Candidate c = p.candidate;
        Outcome o = p.outcome;
        var f = c.facility();
        FacilityBase base = new FacilityBase(String.valueOf(f.getFacilityId()), f.getType().getTypeId(),
                f.getType().getName(), f.getDistrict(), f.getAccessType().name(), f.getName(), f.getAddress(),
                f.getType().getDefaultImage(), new Coordinates(f.getLatitude(), f.getLongitude()));
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
        boolean stored = o.analysisId() != null;
        return new ImprovementItem(base, c.spaceType().name(), c.spaceLabel(), c.selectedSchedule(),
                c.availability(), currentClosing,
                pl.proposedCloseMin() == null ? null : TimeText.format(pl.proposedCloseMin()),
                "GENERATED", reasonFor(c), pl.alternatives(),
                new IntegratedSuggestion(suggestionStatus(pl, transport), pl.integratedSuggestion()), transport, weak,
                stored ? String.valueOf(o.analysisId()) : null,
                new CacheInfo(o.cacheStatus(), stored && o.time() != null
                        ? o.time().atZone(Meta.ZONE).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS) : null),
                limitations(c, transport));
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
        if (c.currentCloseMin() == null) {
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

    private static Warning llmWarning(Prepared p, String reason) {
        return Warning.of(WarningCode.LLM_UNAVAILABLE, reason, facilityId(p));
    }

    private static String facilityId(Prepared p) {
        return String.valueOf(p.candidate.facility().getFacilityId());
    }

    /** (code, facilityId)가 같은 경고는 한 번만 */
    private static void addWarning(Map<String, Warning> warnings, Warning w) {
        warnings.putIfAbsent(w.code() + "|" + w.facilityId(), w);
    }
}
