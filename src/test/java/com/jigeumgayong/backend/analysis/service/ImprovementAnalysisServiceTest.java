package com.jigeumgayong.backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
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

    private Facility facility(AccessType access) {
        return facilityRepository.save(Facility.builder().type(library).sourceType(SourceType.MANUAL)
                .sourceKey("k" + (seq++)).district("수지구").accessType(access).name("시설" + seq)
                .address("용인시 수지구").latitude(new BigDecimal("37.3")).longitude(new BigDecimal("127.1")).build());
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
        return service.analyze(null, null, null, TUE, "19:00", "21:00");
    }

    private static List<String> codes(AnalysisResult r) {
        return r.warnings().stream().map(Warning::code).toList();
    }

    @Test
    void 후보가_없으면_LLM을_호출하지_않는다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        open(f, SpaceType.DEFAULT, 540, 1320); // 22시까지 운영

        AnalysisResult r = run();

        assertThat(r.data().candidateCount()).isZero();
        assertThat(r.data().items()).isEmpty();
        assertThat(llm.calls.get()).isZero();
        assertThat(codes(r)).containsExactly("WEEKLY_SCHEDULE_ONLY");
    }

    @Test
    void 후보가_없고_unknown이_있으면_경고로_알린다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체")); // 행 없음

        AnalysisResult r = run();

        assertThat(r.data().candidateCount()).isZero();
        assertThat(r.data().unknownSpaceCount()).isEqualTo(1);
        assertThat(codes(r)).contains("UNKNOWN_HOURS_EXCLUDED");
        assertThat(llm.calls.get()).isZero();
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
        assertThat(first.integratedSuggestion().status()).isEqualTo("PARTIAL"); // COMPLETE 금지(T21)
        assertThat(first.suggestionId()).isNotNull();
        assertThat(first.cache().createdAt()).isNotNull();
        assertThat(first.transport().status().name()).isEqualTo("NOT_ENABLED");
        assertThat(first.weakTimeAnalysis().status()).isEqualTo("NOT_SUPPORTED");

        assertThat(second.cache().status()).isEqualTo("HIT");
        assertThat(second.suggestionId()).isEqualTo(first.suggestionId());
        assertThat(second.cache().createdAt()).isEqualTo(first.cache().createdAt());
        assertThat(second.alternatives()).isEqualTo(first.alternatives());
        assertThat(llm.calls.get()).isEqualTo(1); // T15
    }

    @Test
    void 처리_결과가_INFO_로그_한_줄로_남고_본문은_찍히지_않는다(CapturedOutput output) {
        Facility f = closedEvening(AccessType.OPEN_HOURS);

        run();
        run();

        String id = String.valueOf(f.getFacilityId());
        assertThat(output.getAll())
                .contains("A04 분석 facilityId=" + id + " space=DEFAULT cache=CREATED llmCalled=true")
                .contains("A04 분석 facilityId=" + id + " space=DEFAULT cache=HIT llmCalled=false")
                .doesNotContain("integratedSuggestion").doesNotContain("Authorization");
    }

    @Test
    void 모델명과_prompt_version_source_hash가_저장된다() {
        closedEvening(AccessType.OPEN_HOURS);
        run();

        AiAnalysis row = aiRepository.findAll().get(0);

        assertThat(row.getModelName()).isEqualTo("fake-model");
        assertThat(row.getPromptVersion()).isEqualTo("imp-v1");
        assertThat(row.getSourceHash()).hasSize(64);
        assertThat(row.getScopeKey()).startsWith("FAC:");
        assertThat(row.getParamsKey()).isEqualTo("S:DEFAULT|D:1|T:1140-1260");
        assertThat(jdbc.queryForObject("select cast(payload as varchar) from ai_analysis", String.class))
                .startsWith("{").contains("\"proposedCloseMin\":1260");
    }

    @Test
    void 같은_시설_요일_시간이어도_spaceType이_다르면_캐시_행이_따로_생긴다() {
        Facility f = facility(AccessType.OPEN_HOURS);
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.GENERAL, "종합자료실"));
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.READING, "열람실"));
        open(f, SpaceType.GENERAL, 540, 1080);
        open(f, SpaceType.READING, 540, 1080);

        AnalysisResult r = run();

        assertThat(r.data().candidateCount()).isEqualTo(2);
        assertThat(r.data().facilityCount()).isEqualTo(1);
        assertThat(r.data().items()).extracting(ImprovementItem::spaceType).containsExactly("GENERAL", "READING");
        assertThat(aiRepository.count()).isEqualTo(2); // T14
        assertThat(r.data().items().get(0).suggestionId()).isNotEqualTo(r.data().items().get(1).suggestionId());
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
        assertThat(aiRepository.findAll().get(0).getPromptVersion()).isEqualTo("imp-v1");
        assertThat(llm.calls.get()).isEqualTo(2);
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
    void ALWAYS_OPEN은_후보에서_제외한다() {
        Facility f = facility(AccessType.ALWAYS_OPEN);
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));

        assertThat(run().data().candidateCount()).isZero();
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

    @Test
    void 요청당_신규_생성은_10건까지이고_초과분은_다음_요청에서_이어서_생성한다() {
        for (int i = 0; i < 12; i++) {
            closedEvening(AccessType.OPEN_HOURS);
        }

        AnalysisResult first = run();
        List<ImprovementItem> items = first.data().items();

        assertThat(items).hasSize(12);
        assertThat(items.subList(0, 10)).allSatisfy(i -> assertThat(i.cache().status()).isEqualTo("CREATED"));
        // facilityId 순서상 뒤쪽 2건이 한도 초과
        assertThat(items.subList(10, 12)).allSatisfy(i -> {
            assertThat(i.proposalStatus()).isEqualTo("UNAVAILABLE");
            assertThat(i.proposalReason()).isEqualTo("이번 요청의 생성 한도 초과");
            assertThat(i.cache().status()).isEqualTo("UNAVAILABLE");
        });
        assertThat(llm.calls.get()).isEqualTo(10);
        assertThat(codes(first)).contains("LLM_UNAVAILABLE");
        assertThat(first.warnings()).filteredOn(w -> w.code().equals("LLM_UNAVAILABLE")).hasSize(2);

        AnalysisResult second = run();

        assertThat(second.data().items().subList(0, 10))
                .allSatisfy(i -> assertThat(i.cache().status()).isEqualTo("HIT")); // HIT는 한도에서 제외
        assertThat(second.data().items().subList(10, 12))
                .allSatisfy(i -> assertThat(i.cache().status()).isEqualTo("CREATED"));
        assertThat(llm.calls.get()).isEqualTo(12);
        assertThat(aiRepository.count()).isEqualTo(12);
    }
}
