package com.jigeumgayong.backend.analysis.repository;

import com.jigeumgayong.backend.analysis.domain.AiAnalysis;
import com.jigeumgayong.backend.analysis.domain.AnalysisType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiAnalysisRepository extends JpaRepository<AiAnalysis, Long> {

    /** A04 캐시 조회 키 (UNIQUE 묶음과 동일) */
    Optional<AiAnalysis> findByScopeKeyAndAnalysisTypeAndParamsKey(
            String scopeKey, AnalysisType analysisType, String paramsKey);
}
