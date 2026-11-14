package com.jigeumgayong.backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.jigeumgayong.backend.analysis.domain.AiAnalysis;
import com.jigeumgayong.backend.analysis.dto.ImprovementAnalysisResponse.ImprovementItem;
import com.jigeumgayong.backend.analysis.repository.AiAnalysisRepository;
import com.jigeumgayong.backend.analysis.service.ImprovementAnalysisService.AnalysisResult;
import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.ChipGroup;
import com.jigeumgayong.backend.facility.domain.DayState;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.FacilitySpace;
import com.jigeumgayong.backend.facility.domain.FacilityType;
import com.jigeumgayong.backend.facility.domain.OperatingHour;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.domain.SpaceType;
import com.jigeumgayong.backend.facility.repository.FacilityAmenityRepository;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.FacilitySpaceRepository;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.global.response.Warning;
import com.jigeumgayong.backend.support.FakeLlmClient;
import com.jigeumgayong.backend.support.FakeLlmConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 컨텍스트(H2) + 가짜 LLM. 트랜잭션 밖에서 캐시를 읽고 쓰는 실제 흐름을 검증한다. */
@SpringBootTest
@Import(FakeLlmConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class ImprovementAnalysisServiceTest {

    static final int TUE = 1;

    @Autowired ImprovementAnalysisService service;
    @Autowired FakeLlmClient llm;
    @Autowired AiAnalysisRepository aiRepository;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FacilitySpaceRepository spaceRepository;
    @Autowired OperatingHourRepository hourRepository;
    @Autowired FacilityAmenityRepository amenityRepository;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ImprovementCache cacheSpy;

    FacilityType library;
    int seq = 0;

    @BeforeEach
    void setUp() {
        llm.reset();
        aiRepository.deleteAll();
        amenityRepository.deleteAll();
        hourRepository.deleteAll();
        spaceRepository.deleteAll();
        facilityRepository.deleteAll();
        typeRepository.deleteAll();
        library = typeRepository.save(FacilityType.builder().name("도서관").chipGroup(ChipGroup.LIBRARY).build());
    }

    /** 마지막으로 만든 시설. run()이 이 시설의 facilityId로 요청한다 */
    Facility target;

    private Facility facility(AccessType access) {
        target = facilityRepository.save(Facility.builder().type(library).sourceType(SourceType.MANUAL)
                .sourceKey("k" + (seq++)).district("수지구").accessType(access).name("시설" + seq)
                .address("용인시 수지구").latitude(new BigDecimal("37.3")).longitude(new BigDecimal("127.1")).build());
        return target;
    }

    /** 화요일 09:00-18:00 DEFAULT 공간을 가진 시설 (19-21시에 닫혀 있음) */
    private Facility closedEvening(AccessType access) {
        Facility f = facility(access);
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        open(f, SpaceType.DEFAULT, 540, 1080);
        return f;
    }

    private void open(Facility f, SpaceType t, int openMin, int closeMin) {
        hourRepository.save(OperatingHour.builder().facilityId(f.getFacilityId()).spaceType(t)
                .dayOfWeek((short) TUE).intervalSeq((short) 1).dayState(DayState.OPEN)
                .openMin((short) openMin).closeMin((short) closeMin).build());
    }

    private AnalysisResult run() {
        return runFor(target.getFacilityId());
    }

    private AnalysisResult runFor(Object facilityId) {
        return service.analyze(facilityId == null ? null : String.valueOf(facilityId), null, null, null, TUE,
                "19:00", "21:00");
    }

    private static List<String> codes(AnalysisResult r) {
        return r.warnings().stream().map(Warning::code).toList();
    }

    @Test
    void 부족한_점이_없으면_LLM을_호출하지_않고_고정_문구를_준다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        open(f, SpaceType.DEFAULT, 540, 1320); // 22시까지 운영, 다른 요일·정류장 정보 없음

        AnalysisResult r = run();

        assertThat(r.data().candidateCount()).isEqualTo(1);
        ImprovementItem item = r.data().items().get(0);
        assertThat(item.availability().status()).isEqualTo("AVAILABLE");
        assertThat(item.proposalStatus()).isEqualTo("UNAVAILABLE");
        assertThat(item.proposalReason()).isEqualTo("현재 확인된 개선 사항이 없습니다.");
        assertThat(item.alternatives()).isEmpty();
        assertThat(item.integratedSuggestion().text()).isEqualTo("현재 확인된 개선 사항이 없습니다.");
        assertThat(item.suggestionId()).isNull();
        assertThat(llm.calls.get()).isZero();
        assertThat(aiRepository.count()).isZero();
        assertThat(codes(r)).contains("WEEKLY_SCHEDULE_ONLY");
    }

    private void openOn(Facility f, SpaceType t, int day, int openMin, int closeMin) {
        hourRepository.save(OperatingHour.builder().facilityId(f.getFacilityId()).spaceType(t)
                .dayOfWeek((short) day).intervalSeq((short) 1).dayState(DayState.OPEN)
                .openMin((short) openMin).closeMin((short) closeMin).build());
    }

    @Test
    void 희망_시간에_이용_가능한_시설도_요일_편차로_개선안이_나온다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        open(f, SpaceType.DEFAULT, 540, 1320); // 화요일 09~22시: 19~21시 이용 가능
        openOn(f, SpaceType.DEFAULT, 5, 540, 780); // 토요일 09~13시 → 평일과 9시간 차이

        AnalysisResult r = run();

        ImprovementItem item = r.data().items().get(0);
        assertThat(item.availability().status()).isEqualTo("AVAILABLE");
        assertThat(item.proposalStatus()).isEqualTo("GENERATED");
        assertThat(item.proposedClosingTime()).isNull(); // 이미 덮이므로 종료 연장 제안은 없다
        assertThat(item.alternatives()).isNotEmpty();
        assertThat(item.proposalReason()).contains("이용할 수 있습니다");
        assertThat(item.cache().status()).isEqualTo("CREATED");
        assertThat(llm.calls.get()).isEqualTo(1);
        // LLM에 넘긴 사실에 요일 편차 관찰이 있다
        assertThat(aiRepository.findAll().get(0).getPayload()).contains("\"basedOn\"");
    }

    @Test
    void 정류장이_적으면_이용_가능한_시설도_교통_관찰로_개선안이_나온다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        open(f, SpaceType.DEFAULT, 540, 1320);
        setBusStops(f, 2);

        AnalysisResult r = run();

        assertThat(r.data().items().get(0).proposalStatus()).isEqualTo("GENERATED");
        assertThat(r.data().items().get(0).transport().status().name()).isEqualTo("AVAILABLE");
        assertThat(llm.calls.get()).isEqualTo(1);
    }

    @Test
    void 운영시간을_모르는_시설은_정보_확인을_제안하는_개선안이_나온다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체")); // 행 없음

        AnalysisResult r = run();

        assertThat(r.data().candidateCount()).isEqualTo(1);
        assertThat(r.data().unknownSpaceCount()).isEqualTo(1);
        ImprovementItem item = r.data().items().get(0);
        assertThat(item.availability().status()).isEqualTo("UNKNOWN");
        assertThat(item.currentClosingTime()).isNull();
        assertThat(item.proposedClosingTime()).isNull();
        assertThat(item.proposalStatus()).isEqualTo("GENERATED");
        assertThat(item.proposalReason()).contains("운영시간을 확인할 수 없습니다");
        assertThat(codes(r)).doesNotContain("UNKNOWN_HOURS_EXCLUDED"); // 이제 제외하지 않고 분석한다
        assertThat(llm.calls.get()).isEqualTo(1);
    }

    @Test
    void 첫_요청은_CREATED_두번째는_HIT이고_LLM은_한_번만_호출된다() {
        closedEvening(AccessType.OPEN_HOURS);

        ImprovementItem first = run().data().items().get(0);
        ImprovementItem second = run().data().items().get(0);

        assertThat(first.cache().status()).isEqualTo("CREATED");
        assertThat(first.proposalStatus()).isEqualTo("GENERATED");
        assertThat(first.proposedClosingTime()).isEqualTo("21:00");
        assertThat(first.currentClosingTime()).isEqualTo("18:00");
        assertThat(first.integratedSuggestion().status()).isEqualTo("PARTIAL"); // 교통 근거 없음(NO_DATA) → PARTIAL
        assertThat(first.suggestionId()).isNotNull();
        assertThat(first.cache().createdAt()).isNotNull();
        assertThat(first.transport().status().name()).isEqualTo("NO_DATA"); // 정류장 수 null (T20)
        assertThat(first.weakTimeAnalysis().status()).isEqualTo("NOT_SUPPORTED");

        assertThat(second.cache().status()).isEqualTo("HIT");
        assertThat(second.suggestionId()).isEqualTo(first.suggestionId());
        assertThat(second.cache().createdAt()).isEqualTo(first.cache().createdAt());
        assertThat(second.alternatives()).isEqualTo(first.alternatives());
        assertThat(second.integratedSuggestion().status()).isEqualTo("PARTIAL");
        assertThat(llm.calls.get()).isEqualTo(1); // T15
    }

    @Test
    void 처리_결과가_INFO_로그_한_줄로_남고_본문은_찍히지_않는다(CapturedOutput output) {
        Facility f = closedEvening(AccessType.OPEN_HOURS);

        run();
        run();

        String id = String.valueOf(f.getFacilityId());
        assertThat(output.getAll())
                .contains("A04 분석 facilityId=" + id + " cache=CREATED llmCalled=true")
                .contains("A04 분석 facilityId=" + id + " cache=HIT llmCalled=false")
                .doesNotContain("integratedSuggestion").doesNotContain("Authorization");
    }

    @Test
    void 모델명과_prompt_version_source_hash가_저장된다() {
        closedEvening(AccessType.OPEN_HOURS);
        run();

        AiAnalysis row = aiRepository.findAll().get(0);

        assertThat(row.getModelName()).isEqualTo("fake-model");
        assertThat(row.getPromptVersion()).isEqualTo("imp-v5");
        assertThat(row.getSourceHash()).hasSize(64);
        assertThat(row.getScopeKey()).startsWith("FAC:");
        assertThat(row.getParamsKey()).isEqualTo("D:1|T:1140-1260"); // 시설 단위라 공간은 키에 없다
        assertThat(row.getSpaceType()).isNull();
        assertThat(jdbc.queryForObject("select cast(payload as varchar) from ai_analysis", String.class))
                .startsWith("{").contains("\"proposedCloseMin\":1260");
    }

    @Test
    void 공간이_여럿이어도_LLM은_한_번이고_캐시_행은_시설당_하나다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.GENERAL, "종합자료실"));
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.READING, "열람실"));
        open(f, SpaceType.GENERAL, 540, 1080);
        open(f, SpaceType.READING, 540, 1080);

        AnalysisResult r = run();

        assertThat(r.data().candidateCount()).isEqualTo(2);
        assertThat(r.data().facilityCount()).isEqualTo(1);
        assertThat(r.data().items()).extracting(ImprovementItem::spaceType).containsExactly("GENERAL", "READING");
        assertThat(aiRepository.count()).isEqualTo(1);
        assertThat(llm.calls.get()).isEqualTo(1);
        // 공간별 항목이 같은 캐시 행과 같은 시설 종합 문장을 공유한다
        assertThat(r.data().items().get(0).suggestionId()).isEqualTo(r.data().items().get(1).suggestionId());
        assertThat(r.data().items().get(0).integratedSuggestion().text())
                .isEqualTo(r.data().items().get(1).integratedSuggestion().text());
        assertThat(r.data().items()).allMatch(i -> "GENERATED".equals(i.proposalStatus()));
    }

    @Test
    void 운영시간이_바뀌면_source_hash가_달라져_같은_행을_재생성한다() {
        Facility f = closedEvening(AccessType.OPEN_HOURS);
        String firstId = run().data().items().get(0).suggestionId();

        hourRepository.deleteAll();
        open(f, SpaceType.DEFAULT, 540, 1020); // 18시 → 17시 마감
        AnalysisResult r = run();

        ImprovementItem item = r.data().items().get(0);
        assertThat(item.cache().status()).isEqualTo("CREATED");
        assertThat(item.suggestionId()).isEqualTo(firstId); // 같은 행 UPDATE
        assertThat(item.currentClosingTime()).isEqualTo("17:00");
        assertThat(aiRepository.count()).isEqualTo(1);
        assertThat(codes(r)).contains("CACHE_INVALIDATED");
        assertThat(llm.calls.get()).isEqualTo(2); // T16
    }

    @Test
    void prompt_version이_다르면_재생성한다() {
        closedEvening(AccessType.OPEN_HOURS);
        run();
        AiAnalysis row = aiRepository.findAll().get(0);
        row.regenerate(row.getPayload(), row.getSourceHash(), "imp-v0", row.getModelName());
        aiRepository.saveAndFlush(row);

        ImprovementItem item = run().data().items().get(0);

        assertThat(item.cache().status()).isEqualTo("CREATED");
        assertThat(aiRepository.findAll().get(0).getPromptVersion()).isEqualTo("imp-v5");
        assertThat(llm.calls.get()).isEqualTo(2);
    }

    @Test
    void 기존_imp_v2_캐시는_같은_행을_imp_v3로_재생성한다() {
        closedEvening(AccessType.OPEN_HOURS);
        String firstId = run().data().items().get(0).suggestionId();
        AiAnalysis row = aiRepository.findAll().get(0);
        row.regenerate(row.getPayload(), row.getSourceHash(), "imp-v2", row.getModelName());
        aiRepository.saveAndFlush(row);

        AnalysisResult r = run();

        assertThat(r.data().items().get(0).cache().status()).isEqualTo("CREATED");
        assertThat(r.data().items().get(0).suggestionId()).isEqualTo(firstId);
        assertThat(aiRepository.count()).isEqualTo(1);
        assertThat(aiRepository.findAll().get(0).getPromptVersion()).isEqualTo("imp-v5");
        assertThat(codes(r)).contains("CACHE_INVALIDATED");
        assertThat(llm.calls.get()).isEqualTo(2);
    }

    private void setBusStops(Facility f, Integer count) {
        f.updateBusStops(count, java.time.LocalDate.of(2026, 11, 1));
        facilityRepository.save(f);
    }

    @Test
    void 정류장_수가_있으면_AVAILABLE이고_facts에_들어간다() {
        Facility f = closedEvening(AccessType.OPEN_HOURS);
        setBusStops(f, 4);
        java.util.List<String> inputs = new java.util.concurrent.CopyOnWriteArrayList<>();
        llm.responder = facts -> {
            inputs.add(facts);
            return FakeLlmClient.validResponse(facts).replace("운영시간 조정을 검토할 수 있습니다.",
                    "시설 500m 내 정류장은 4개이며, 실제 운행은 별도 확인이 필요합니다.");
        };

        AnalysisResult r = run();
        ImprovementItem item = r.data().items().get(0);

        assertThat(item.transport().status().name()).isEqualTo("AVAILABLE");
        assertThat(item.transport().stopCount()).isEqualTo(4);
        assertThat(item.transport().radiusMeters()).isEqualTo(500);
        assertThat(item.transport().grade()).isNull();
        assertThat(item.integratedSuggestion().status()).isEqualTo("COMPLETE");
        assertThat(item.integratedSuggestion().text()).contains("정류장은 4개");
        assertThat(item.weakTimeAnalysis().status()).isEqualTo("NOT_SUPPORTED");
        assertThat(item.limitations()).contains("정류장 개수 외 시간대별 운행자료가 없습니다.");
        assertThat(inputs.get(0)).contains("\"busStopCount\":4").contains("\"busStopRadiusMeters\":500");
        assertThat(codes(r)).doesNotContain("TRANSPORT_DATA_UNAVAILABLE").contains("GRADE_RULE_NOT_DEFINED");
        Warning grade = r.warnings().stream().filter(w -> w.code().equals("GRADE_RULE_NOT_DEFINED")).findFirst().orElseThrow();
        assertThat(grade.message()).isEqualTo("정류장 수는 제공하지만 등급 기준은 미정입니다."); // A05와 같은 문구
        assertThat(grade.facilityId()).isEqualTo(item.facility().facilityId());
    }

    /** 입력에 busStopCount가 있으면 그 값으로 정류장 문장을 넣는 가짜 LLM (없으면 기본 응답) */
    private void respondWithStops() {
        llm.responder = facts -> {
            String base = FakeLlmClient.validResponse(facts);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"busStopCount\":(\\d+)").matcher(facts);
            return m.find() ? base.replace("운영시간 조정을 검토할 수 있습니다.",
                    "시설 500m 내 정류장은 " + m.group(1) + "개이며, 실제 운행은 별도 확인이 필요합니다.") : base;
        };
    }

    @Test
    void 정류장_수를_문장에_반영하면_CREATED와_HIT_모두_COMPLETE다() {
        setBusStops(closedEvening(AccessType.OPEN_HOURS), 4);
        respondWithStops();

        ImprovementItem first = run().data().items().get(0);
        ImprovementItem second = run().data().items().get(0);

        assertThat(first.cache().status()).isEqualTo("CREATED");
        assertThat(first.integratedSuggestion().status()).isEqualTo("COMPLETE");
        assertThat(second.cache().status()).isEqualTo("HIT");
        assertThat(second.integratedSuggestion().status()).isEqualTo("COMPLETE");
        assertThat(llm.calls.get()).isEqualTo(1);
    }

    @Test
    void 정류장_0개도_문장에_반영하면_COMPLETE다() {
        setBusStops(closedEvening(AccessType.OPEN_HOURS), 0);
        respondWithStops();

        ImprovementItem item = run().data().items().get(0);

        assertThat(item.transport().stopCount()).isZero();
        assertThat(item.integratedSuggestion().text()).contains("정류장은 0개");
        assertThat(item.integratedSuggestion().status()).isEqualTo("COMPLETE");
    }

    @Test
    void AVAILABLE이어도_문장에_정류장_수가_없으면_CREATED와_HIT_모두_PARTIAL이다() {
        setBusStops(closedEvening(AccessType.OPEN_HOURS), 4); // 기본 응답은 정류장을 언급하지 않는다

        ImprovementItem first = run().data().items().get(0);
        ImprovementItem second = run().data().items().get(0);

        assertThat(first.transport().status().name()).isEqualTo("AVAILABLE");
        assertThat(first.integratedSuggestion().status()).isEqualTo("PARTIAL");
        assertThat(second.cache().status()).isEqualTo("HIT");
        assertThat(second.integratedSuggestion().status()).isEqualTo("PARTIAL");
    }

    @Test
    void 교통_자료가_없어지면_기존_COMPLETE_문장을_재사용하지_않는다() {
        // T21: source_hash에 정류장 수가 들어 있어 NO_DATA로 바뀌면 재생성된다
        Facility f = closedEvening(AccessType.OPEN_HOURS);
        setBusStops(f, 4);
        respondWithStops();
        assertThat(run().data().items().get(0).integratedSuggestion().status()).isEqualTo("COMPLETE");

        setBusStops(f, null);
        ImprovementItem item = run().data().items().get(0);

        assertThat(item.transport().status().name()).isEqualTo("NO_DATA");
        assertThat(item.cache().status()).isEqualTo("CREATED");
        assertThat(item.integratedSuggestion().text()).doesNotContain("정류장");
        assertThat(item.integratedSuggestion().status()).isEqualTo("PARTIAL");
        assertThat(llm.calls.get()).isEqualTo(2);
    }

    @Test
    void 같은_시설의_공간이_여럿이어도_등급_경고는_시설당_한_번이다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.GENERAL, "종합자료실"));
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.READING, "열람실"));
        open(f, SpaceType.GENERAL, 540, 1080);
        open(f, SpaceType.READING, 540, 1080);
        setBusStops(f, 3);

        AnalysisResult r = run();

        assertThat(r.data().items()).hasSize(2);
        assertThat(codes(r).stream().filter("GRADE_RULE_NOT_DEFINED"::equals)).hasSize(1);
    }

    @Test
    void 정류장_수가_0이면_NO_DATA가_아니라_AVAILABLE_0이다() {
        setBusStops(closedEvening(AccessType.OPEN_HOURS), 0);

        ImprovementItem item = run().data().items().get(0);

        assertThat(item.transport().status().name()).isEqualTo("AVAILABLE");
        assertThat(item.transport().stopCount()).isZero();
    }

    @Test
    void 정류장_수가_null이면_NO_DATA이고_facts에_정류장이_없으며_A05처럼_경고한다() {
        closedEvening(AccessType.OPEN_HOURS);
        java.util.List<String> inputs = new java.util.concurrent.CopyOnWriteArrayList<>();
        llm.responder = facts -> {
            inputs.add(facts);
            return FakeLlmClient.validResponse(facts);
        };

        AnalysisResult r = run();

        ImprovementItem item = r.data().items().get(0);
        assertThat(item.transport().status().name()).isEqualTo("NO_DATA");
        assertThat(item.transport().stopCount()).isNull();
        assertThat(inputs.get(0)).doesNotContain("busStop");
        assertThat(codes(r)).contains("TRANSPORT_DATA_UNAVAILABLE").doesNotContain("GRADE_RULE_NOT_DEFINED");
        assertThat(item.limitations()).contains("교통·운행 자료를 반영하지 않았습니다.");
    }

    @Test
    void 정류장_수가_바뀌면_source_hash가_달라져_같은_행을_재생성한다() {
        Facility f = closedEvening(AccessType.OPEN_HOURS);
        setBusStops(f, 4);
        String firstId = run().data().items().get(0).suggestionId();
        String hash = aiRepository.findAll().get(0).getSourceHash();
        assertThat(run().data().items().get(0).cache().status()).isEqualTo("HIT");

        setBusStops(f, 6);
        AnalysisResult r = run();

        ImprovementItem item = r.data().items().get(0);
        assertThat(item.cache().status()).isEqualTo("CREATED");
        assertThat(item.suggestionId()).isEqualTo(firstId);
        assertThat(aiRepository.count()).isEqualTo(1);
        assertThat(aiRepository.findAll().get(0).getSourceHash()).isNotEqualTo(hash);
        assertThat(codes(r)).contains("CACHE_INVALIDATED");
        assertThat(llm.calls.get()).isEqualTo(2); // T16
    }

    @Test
    void LLM이_정류장_수로_배차_정보를_말하면_저장하지_않는다() {
        setBusStops(closedEvening(AccessType.OPEN_HOURS), 4);
        llm.responder = facts -> FakeLlmClient.validResponse(facts).replace("운영시간 조정을 검토할 수 있습니다.",
                "정류장 4개가 있어 4분 간격 배차가 가능합니다.");

        ImprovementItem item = run().data().items().get(0);

        assertThat(item.proposalStatus()).isEqualTo("UNAVAILABLE");
        assertThat(aiRepository.count()).isZero(); // T30
    }

    @Test
    void 손상된_payload는_HIT이_아니라_재생성한다() {
        closedEvening(AccessType.OPEN_HOURS);
        run();
        AiAnalysis row = aiRepository.findAll().get(0);
        // hash·version은 맞지만 alternatives가 숫자 배열인 손상 payload (T18)
        row.regenerate("{\"proposedCloseMin\":1260,\"alternatives\":[1,2],\"integratedSuggestion\":\"x\"}",
                row.getSourceHash(), row.getPromptVersion(), row.getModelName());
        aiRepository.saveAndFlush(row);

        ImprovementItem item = run().data().items().get(0);

        assertThat(item.cache().status()).isEqualTo("CREATED");
        assertThat(item.alternatives()).allSatisfy(a -> assertThat(a).isInstanceOf(String.class));
        assertThat(llm.calls.get()).isEqualTo(2);
    }

    @Test
    void LLM이_실패하면_200_부분_응답이고_저장하지_않는다() {
        Facility f = closedEvening(AccessType.OPEN_HOURS);
        llm.fail = true;

        AnalysisResult r = run();

        ImprovementItem item = r.data().items().get(0);
        assertThat(r.data().candidateCount()).isEqualTo(1);
        assertThat(item.proposalStatus()).isEqualTo("UNAVAILABLE");
        assertThat(item.proposalReason()).isEqualTo("AI 제안을 생성하지 못했습니다.");
        assertThat(item.proposedClosingTime()).isNull();
        assertThat(item.alternatives()).isEmpty();
        assertThat(item.integratedSuggestion().status()).isEqualTo("UNAVAILABLE");
        assertThat(item.integratedSuggestion().text()).isNull();
        assertThat(item.suggestionId()).isNull();
        assertThat(item.cache().status()).isEqualTo("UNAVAILABLE");
        assertThat(item.cache().createdAt()).isNull();
        // 계산된 값은 유지
        assertThat(item.availability().status()).isEqualTo("UNAVAILABLE");
        assertThat(item.currentClosingTime()).isEqualTo("18:00");
        assertThat(item.facility().facilityId()).isEqualTo(String.valueOf(f.getFacilityId()));
        assertThat(item.transport()).isNotNull();
        assertThat(codes(r)).contains("LLM_UNAVAILABLE");
        assertThat(aiRepository.count()).isZero(); // T19
    }

    @Test
    void LLM이_검증을_통과하지_못하는_응답을_주면_저장하지_않고_UNAVAILABLE이다() {
        closedEvening(AccessType.OPEN_HOURS);
        for (String bad : new String[] { "not json", "{\"proposedClosingTime\":\"17:00\",\"alternatives\":[\"a\"],"
                + "\"integratedSuggestion\":\"b\"}", "{\"proposedClosingTime\":\"21:00\",\"alternatives\":[\"효과 35%\"],"
                + "\"integratedSuggestion\":\"b\"}" }) {
            llm.responder = in -> bad;

            ImprovementItem item = run().data().items().get(0);

            assertThat(item.proposalStatus()).as(bad).isEqualTo("UNAVAILABLE");
            assertThat(aiRepository.count()).isZero();
        }
    }

    @Test
    void 캐시_저장만_실패하면_생성_결과는_유지하고_NOT_STORED다() {
        closedEvening(AccessType.OPEN_HOURS);
        doThrow(new DataAccessResourceFailureException("db down")).when(cacheSpy)
                .save(any(), anyLong(), any(), anyString(), anyString(), anyString(), anyString(), anyString());

        AnalysisResult r = run();

        ImprovementItem item = r.data().items().get(0);
        assertThat(item.proposalStatus()).isEqualTo("GENERATED");
        assertThat(item.alternatives()).isNotEmpty();
        assertThat(item.suggestionId()).isNull();
        assertThat(item.cache().status()).isEqualTo("NOT_STORED");
        assertThat(item.cache().createdAt()).isNull();
        assertThat(codes(r)).contains("CACHE_WRITE_FAILED");
        assertThat(aiRepository.count()).isZero(); // T19
    }

    @Test
    void 캐시_조회가_실패해도_생성을_시도하고_CACHE_READ_FAILED를_알린다() {
        closedEvening(AccessType.OPEN_HOURS);
        doThrow(new DataAccessResourceFailureException("db down")).when(cacheSpy).find(anyString(), anyString());

        AnalysisResult r = run();

        assertThat(r.data().items().get(0).proposalStatus()).isEqualTo("GENERATED");
        assertThat(codes(r)).contains("CACHE_READ_FAILED");
    }

    @Test
    void RESERVATION은_예약_필요를_명시한다() {
        closedEvening(AccessType.RESERVATION);

        ImprovementItem item = run().data().items().get(0);

        assertThat(item.availability().requiresReservation()).isTrue();
        assertThat(item.proposalReason()).contains("예약");
        assertThat(item.integratedSuggestion().text()).contains("예약");
        assertThat(item.limitations()).anyMatch(l -> l.contains("예약"));
    }

    @Test
    void ALWAYS_OPEN은_항상_이용_가능한_공간이고_부족한_점이_없으면_고정_문구다() {
        Facility f = facility(AccessType.ALWAYS_OPEN);
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));

        AnalysisResult r = run();

        assertThat(r.data().candidateCount()).isEqualTo(1);
        assertThat(r.data().items().get(0).availability().status()).isEqualTo("AVAILABLE");
        assertThat(r.data().items().get(0).selectedSchedule().intervals().get(0).closeTime()).isEqualTo("24:00");
        assertThat(r.data().items().get(0).proposalReason()).isEqualTo("현재 확인된 개선 사항이 없습니다.");
        assertThat(llm.calls.get()).isZero();
    }

    @Test
    void UNIQUE_충돌이_나면_500이_아니라_저장된_행을_다시_읽어_HIT으로_수렴한다() {
        closedEvening(AccessType.OPEN_HOURS);
        String savedId = run().data().items().get(0).suggestionId(); // 행을 하나 만들어 둔다
        llm.calls.set(0);
        // 다른 서버가 방금 저장한 상황: 조회 2번(요청 시작, 락 안 재확인)은 비어 보이고 INSERT에서 UNIQUE 충돌
        doReturn(Optional.empty()).doReturn(Optional.empty()).doCallRealMethod()
                .when(cacheSpy).find(anyString(), anyString());

        ImprovementItem item = run().data().items().get(0);

        assertThat(item.cache().status()).isEqualTo("HIT");
        assertThat(item.suggestionId()).isEqualTo(savedId);
        assertThat(item.proposalStatus()).isEqualTo("GENERATED");
        assertThat(llm.calls.get()).isEqualTo(1); // 충돌 전까지는 생성했다
        assertThat(aiRepository.count()).isEqualTo(1);
    }

    @Test
    void 동시_동일_요청이어도_LLM은_한_번이고_행은_하나다() throws Exception {
        closedEvening(AccessType.OPEN_HOURS);
        llm.delayMillis = 400;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<AnalysisResult>> futures = List.of(
                    pool.submit(() -> { go.await(); return run(); }),
                    pool.submit(() -> { go.await(); return run(); }));
            go.countDown();

            List<String> statuses = List.of(
                    futures.get(0).get().data().items().get(0).cache().status(),
                    futures.get(1).get().data().items().get(0).cache().status());

            assertThat(statuses).containsExactlyInAnyOrder("CREATED", "HIT"); // T17
            assertThat(llm.calls.get()).isEqualTo(1);
            assertThat(aiRepository.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // --- facilityId ---

    private static String errorField(BusinessException e) {
        return e.getDetails().get(0).field();
    }

    @Test
    void 지정한_시설만_분석하고_다른_시설은_LLM을_호출하지_않는다() {
        closedEvening(AccessType.OPEN_HOURS);
        Facility lib = facility(AccessType.OPEN_HOURS);
        spaceRepository.save(new FacilitySpace(lib.getFacilityId(), SpaceType.GENERAL, "종합자료실"));
        spaceRepository.save(new FacilitySpace(lib.getFacilityId(), SpaceType.READING, "열람실"));
        open(lib, SpaceType.GENERAL, 540, 1080);
        open(lib, SpaceType.READING, 540, 1080);
        closedEvening(AccessType.OPEN_HOURS);

        AnalysisResult r = runFor(lib.getFacilityId());

        // items는 공간별 배열: 도서관은 종합자료실·열람실 2개
        assertThat(r.data().items()).extracting(ImprovementItem::spaceType).containsExactly("GENERAL", "READING");
        assertThat(r.data().items()).allSatisfy(i -> assertThat(i.facility().facilityId())
                .isEqualTo(String.valueOf(lib.getFacilityId())));
        assertThat(r.data().facilityCount()).isEqualTo(1);
        assertThat(llm.calls.get()).isEqualTo(1); // 시설당 LLM 1회
        assertThat(aiRepository.count()).isEqualTo(1);
    }

    @Test
    void facilityId를_지정하면_criteria의_district가_달라도_후보가_되고_criteria는_그대로_돌려준다() {
        Facility f = closedEvening(AccessType.OPEN_HOURS); // 수지구

        AnalysisResult r = service.analyze(String.valueOf(f.getFacilityId()), "없는이름", "기흥구", null, TUE,
                "19:00", "21:00");

        assertThat(r.data().items()).hasSize(1);
        assertThat(r.data().criteria().district()).isEqualTo("기흥구");
        assertThat(r.data().criteria().keyword()).isEqualTo("없는이름");
    }

    @Test
    void facilityId_지정_요청을_다시_보내면_HIT이다() {
        Facility f = closedEvening(AccessType.OPEN_HOURS);

        ImprovementItem first = runFor(f.getFacilityId()).data().items().get(0);
        ImprovementItem second = runFor(f.getFacilityId()).data().items().get(0);

        assertThat(first.cache().status()).isEqualTo("CREATED");
        assertThat(second.cache().status()).isEqualTo("HIT");
        assertThat(second.suggestionId()).isEqualTo(first.suggestionId());
        assertThat(llm.calls.get()).isEqualTo(1);
    }

    @Test
    void facilityId가_공간이_없는_시설이면_빈_items다() {
        Facility f = facility(AccessType.OPEN_HOURS);

        AnalysisResult r = runFor(f.getFacilityId());

        assertThat(r.data().items()).isEmpty();
        assertThat(r.data().candidateCount()).isZero();
        assertThat(r.data().facilityCount()).isZero();
        assertThat(llm.calls.get()).isZero();
    }

    @Test
    void facilityId_시설에_부족한_점이_없으면_다른_시설과_관계없이_LLM을_호출하지_않는다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        open(f, SpaceType.DEFAULT, 540, 1320);
        closedEvening(AccessType.OPEN_HOURS); // 다른 시설은 불일치가 있어도 대상이 아니다

        AnalysisResult r = runFor(f.getFacilityId());

        assertThat(r.data().items()).hasSize(1);
        assertThat(r.data().items().get(0).proposalReason()).isEqualTo("현재 확인된 개선 사항이 없습니다.");
        assertThat(llm.calls.get()).isZero();
    }

    @Test
    void 없는_facilityId는_FACILITY_NOT_FOUND다() {
        closedEvening(AccessType.OPEN_HOURS);

        assertThatThrownBy(() -> runFor(999_999L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FACILITY_NOT_FOUND));
        assertThat(llm.calls.get()).isZero();
    }

    @Test
    void facilityId_형식이_틀리면_INVALID_REQUEST이고_field는_facilityId다() {
        for (String bad : new String[] { "", "abc", "0", "-1", "01", " 1", "1.0", "9223372036854775808" }) {
            assertThatThrownBy(() -> runFor(bad))
                    .as(bad)
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                        assertThat(errorField(e)).isEqualTo("facilityId");
                    });
        }
        assertThat(llm.calls.get()).isZero();
    }

    @Test
    void facilityId가_null이면_INVALID_REQUEST_REQUIRED다() {
        assertThatThrownBy(() -> runFor(null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(errorField(e)).isEqualTo("facilityId");
                    assertThat(e.getDetails().get(0).reason()).isEqualTo("REQUIRED");
                });
    }

    @Test
    void facilityId_형식_오류가_criteria_오류보다_먼저다() {
        assertThatThrownBy(() -> service.analyze("abc", null, null, null, 9, "19:00", "21:00"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(errorField(e)).isEqualTo("facilityId"));
    }

    @Test
    void facilityId를_지정해도_criteria_값_오류는_400이다() {
        Facility f = closedEvening(AccessType.OPEN_HOURS);

        assertThatThrownBy(() -> service.analyze(String.valueOf(f.getFacilityId()), null, "없는구", null, TUE,
                "19:00", "21:00"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(errorField(e)).isEqualTo("criteria.district");
                });
    }
}
