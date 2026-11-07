package com.jigeumgayong.backend.analysis.domain;

import com.jigeumgayong.backend.facility.domain.SpaceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * AI 분석 캐시 (docs/10 §7).
 * facility_id, (facility_id, space_type) FK와 CHECK는 DDL(db/schema-postgres.sql)이 책임진다.
 * scope_key ↔ facility_id 일치(R09)는 서비스(A04)에서 검증한다.
 */
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Entity
@Table(name = "ai_analysis", uniqueConstraints = @UniqueConstraint(
        name = "uq_ai_analysis_scope_type_params",
        columnNames = {"scope_key", "analysis_type", "params_key"}))
public class AiAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_id")
    private Long analysisId;

    /** FAC:12 / DIST:수지구 / ALL */
    @Column(name = "scope_key", nullable = false, length = 40)
    private String scopeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_type", nullable = false, length = 30)
    private AnalysisType analysisType;

    /** IMPROVEMENT만 사용 (S:GENERAL|D:1|T:1140-1260), 나머지는 빈 문자열 */
    @Column(name = "params_key", nullable = false, length = 40)
    private String paramsKey;

    /** 시설·공간 단위 행에서만 채움 */
    @Column(name = "facility_id")
    private Long facilityId;

    /** 공간 단위 행(IMPROVEMENT)에서만 채움 */
    @Enumerated(EnumType.STRING)
    @Column(name = "space_type", length = 20)
    private SpaceType spaceType;

    /** 종류별 결과 JSON 객체 (PostgreSQL JSONB) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    /** LLM 입력 근거의 SHA-256 (64자 hex) */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_hash", nullable = false, length = 64)
    private String sourceHash;

    @Column(name = "prompt_version", nullable = false, length = 20)
    private String promptVersion;

    @Column(name = "model_name", length = 50)
    private String modelName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private AiAnalysis(String scopeKey, AnalysisType analysisType, String paramsKey,
                       Long facilityId, SpaceType spaceType, String payload,
                       String sourceHash, String promptVersion, String modelName) {
        this.scopeKey = scopeKey;
        this.analysisType = analysisType;
        this.paramsKey = paramsKey == null ? "" : paramsKey;
        this.facilityId = facilityId;
        this.spaceType = spaceType;
        this.payload = payload;
        this.sourceHash = sourceHash;
        this.promptVersion = promptVersion;
        this.modelName = modelName;
    }

    /** 캐시 MISS(해시·프롬프트 버전 변경) 시 재생성 결과로 덮어쓴다. updated_at은 자동 갱신. */
    public void regenerate(String payload, String sourceHash, String promptVersion, String modelName) {
        this.payload = payload;
        this.sourceHash = sourceHash;
        this.promptVersion = promptVersion;
        this.modelName = modelName;
    }
}
