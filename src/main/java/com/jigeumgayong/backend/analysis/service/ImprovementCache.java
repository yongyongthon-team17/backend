package com.jigeumgayong.backend.analysis.service;

import com.jigeumgayong.backend.analysis.domain.AiAnalysis;
import com.jigeumgayong.backend.analysis.domain.AnalysisType;
import com.jigeumgayong.backend.analysis.repository.AiAnalysisRepository;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * IMPROVEMENT 캐시 키 생성·조회·저장과 키별 인메모리 락.
 * 조회/저장은 각각 짧은 트랜잭션(리포지토리 단위)이라 LLM 호출 중에는 DB 연결을 잡지 않는다.
 * 락은 단일 서버에서 같은 키의 중복 LLM 호출(과금)을 막는다. 다중 서버에서는 UNIQUE 충돌 후 재조회가 안전망이다.
 */
@Component
@RequiredArgsConstructor
public class ImprovementCache {

    private final AiAnalysisRepository repository;
    // 키 수(시설×공간×요일×구간)만큼만 자라므로 해커톤 규모에서는 정리하지 않는다
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    /** 10번 §7: FAC:12 */
    static String scopeKey(long facilityId) {
        return "FAC:" + facilityId;
    }

    /** 10번 §7: S:GENERAL|D:1|T:1140-1260 (공간|요일|희망 분 구간) */
    static String paramsKey(SpaceType space, int dayOfWeek, int startMin, int endMin) {
        return "S:" + space.name() + "|D:" + dayOfWeek + "|T:" + startMin + "-" + endMin;
    }

    Optional<AiAnalysis> find(String scopeKey, String paramsKey) {
        return repository.findByScopeKeyAndAnalysisTypeAndParamsKey(scopeKey, AnalysisType.IMPROVEMENT, paramsKey);
    }

    /** existing이 있으면 같은 행을 재생성(UPDATE), 없으면 INSERT. 동시 INSERT면 DataIntegrityViolationException. */
    AiAnalysis save(AiAnalysis existing, long facilityId, SpaceType space, String paramsKey,
                    String payloadJson, String sourceHash, String promptVersion, String modelName) {
        if (existing != null) {
            existing.regenerate(payloadJson, sourceHash, promptVersion, modelName);
            return repository.saveAndFlush(existing);
        }
        return repository.saveAndFlush(AiAnalysis.builder()
                .scopeKey(scopeKey(facilityId)).analysisType(AnalysisType.IMPROVEMENT).paramsKey(paramsKey)
                .facilityId(facilityId).spaceType(space)
                .payload(payloadJson).sourceHash(sourceHash).promptVersion(promptVersion).modelName(modelName)
                .build());
    }

    <T> T withLock(String key, Supplier<T> action) {
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            return action.get();
        }
    }
}
