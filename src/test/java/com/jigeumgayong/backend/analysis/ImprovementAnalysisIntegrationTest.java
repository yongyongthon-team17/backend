package com.jigeumgayong.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jigeumgayong.backend.analysis.repository.AiAnalysisRepository;
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
import com.jigeumgayong.backend.support.FakeLlmClient;
import com.jigeumgayong.backend.support.FakeLlmConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 실제 컨텍스트(H2) + 가짜 LLM + JSON 직렬화 + 공통 에러 핸들러까지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeLlmConfig.class)
class ImprovementAnalysisIntegrationTest {

    static final String URL = "/api/v1/improvement-analyses";
    static final String CRITERIA = """
            {"district":"수지구","dayOfWeek":1,"startTime":"19:00","endTime":"21:00"}""";

    @Autowired MockMvc mvc;
    @Autowired FakeLlmClient llm;
    @Autowired AiAnalysisRepository aiRepository;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FacilitySpaceRepository spaceRepository;
    @Autowired OperatingHourRepository hourRepository;
    @Autowired FacilityAmenityRepository amenityRepository;

    Facility facility;

    @BeforeEach
    void setUp() {
        llm.reset();
        aiRepository.deleteAll();
        amenityRepository.deleteAll();
        hourRepository.deleteAll();
        spaceRepository.deleteAll();
        facilityRepository.deleteAll();
        typeRepository.deleteAll();
        FacilityType type = typeRepository.save(FacilityType.builder().name("도서관")
                .chipGroup(ChipGroup.LIBRARY).defaultImage("/img/lib.png").build());
        facility = facilityRepository.save(Facility.builder().type(type).sourceType(SourceType.MANUAL)
                .sourceKey("it-1").district("수지구").accessType(AccessType.OPEN_HOURS).name("예시 공공도서관")
                .address("용인시 수지구 예시 주소").latitude(new BigDecimal("37.32")).longitude(new BigDecimal("127.1"))
                .build());
        spaceRepository.save(new FacilitySpace(facility.getFacilityId(), SpaceType.GENERAL, "종합자료실"));
        hourRepository.save(OperatingHour.builder().facilityId(facility.getFacilityId()).spaceType(SpaceType.GENERAL)
                .dayOfWeek((short) 1).intervalSeq((short) 1).dayState(DayState.OPEN)
                .openMin((short) 540).closeMin((short) 1080).build());
    }

    @Test
    void CREATED_다음_HIT_json_계약() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(req(CRITERIA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.criteria.keyword").value((Object) null))
                .andExpect(jsonPath("$.data.criteria.facilityTypeIds").isEmpty())
                .andExpect(jsonPath("$.data.criteria.endTime").value("21:00"))
                .andExpect(jsonPath("$.data.candidateCount").value(1))
                .andExpect(jsonPath("$.data.facilityCount").value(1))
                .andExpect(jsonPath("$.data.unknownSpaceCount").value(0))
                .andExpect(jsonPath("$.data.items[0].facility.facilityId")
                        .value(String.valueOf(facility.getFacilityId())))
                .andExpect(jsonPath("$.data.items[0].facility.imageUrl").value("/img/lib.png"))
                .andExpect(jsonPath("$.data.items[0].facility.coordinates.latitude").value(37.32))
                .andExpect(jsonPath("$.data.items[0].spaceType").value("GENERAL"))
                .andExpect(jsonPath("$.data.items[0].selectedSchedule.intervals[0].closeTime").value("18:00"))
                .andExpect(jsonPath("$.data.items[0].availability.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].availability.unavailableIntervals[0].startTime").value("19:00"))
                .andExpect(jsonPath("$.data.items[0].currentClosingTime").value("18:00"))
                .andExpect(jsonPath("$.data.items[0].proposedClosingTime").value("21:00"))
                .andExpect(jsonPath("$.data.items[0].proposalStatus").value("GENERATED"))
                .andExpect(jsonPath("$.data.items[0].alternatives[0]").isString())
                .andExpect(jsonPath("$.data.items[0].integratedSuggestion.status").value("PARTIAL"))
                .andExpect(jsonPath("$.data.items[0].transport.status").value("NO_DATA"))
                .andExpect(jsonPath("$.data.items[0].transport.radiusMeters").value(500))
                .andExpect(jsonPath("$.data.items[0].transport.stopCount").value((Object) null))
                .andExpect(jsonPath("$.data.items[0].weakTimeAnalysis.status").value("NOT_SUPPORTED"))
                .andExpect(jsonPath("$.data.items[0].weakTimeAnalysis.periods").isEmpty())
                .andExpect(jsonPath("$.data.items[0].suggestionId").isString())
                .andExpect(jsonPath("$.data.items[0].cache.status").value("CREATED"))
                .andExpect(jsonPath("$.data.items[0].cache.createdAt").isString())
                .andExpect(jsonPath("$.data.items[0].limitations").isNotEmpty())
                .andExpect(jsonPath("$.data.disclaimer").isString())
                .andExpect(jsonPath("$.meta.warnings[0].code").value("WEEKLY_SCHEDULE_ONLY"));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(req(CRITERIA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].cache.status").value("HIT"));

        assertThat(llm.calls.get()).isEqualTo(1);
        assertThat(aiRepository.count()).isEqualTo(1);
    }

    @Test
    void LLM이_실패해도_200이고_후보_정보는_유지된다() throws Exception {
        llm.fail = true;

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(req(CRITERIA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateCount").value(1))
                .andExpect(jsonPath("$.data.items[0].proposalStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].proposedClosingTime").value((Object) null))
                .andExpect(jsonPath("$.data.items[0].alternatives").isEmpty())
                .andExpect(jsonPath("$.data.items[0].integratedSuggestion.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].integratedSuggestion.text").value((Object) null))
                .andExpect(jsonPath("$.data.items[0].suggestionId").value((Object) null))
                .andExpect(jsonPath("$.data.items[0].cache.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].cache.createdAt").value((Object) null))
                .andExpect(jsonPath("$.data.items[0].availability.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.meta.warnings[?(@.code=='LLM_UNAVAILABLE')].facilityId")
                        .value(String.valueOf(facility.getFacilityId())));
    }

    @Test
    void 관찰이_없으면_LLM_없이_고정_문구로_200이다() throws Exception {
        // 10~12시는 09~18시 운영 안이고 다른 요일 정보·정류장 수도 없어 부족한 점이 하나도 없다
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(req("{\"dayOfWeek\":1,\"startTime\":\"10:00\",\"endTime\":\"12:00\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateCount").value(1))
                .andExpect(jsonPath("$.data.facilityCount").value(1))
                .andExpect(jsonPath("$.data.items[0].availability.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].proposalStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].proposalReason").value("현재 확인된 개선 사항이 없습니다."))
                .andExpect(jsonPath("$.data.items[0].alternatives").isEmpty())
                .andExpect(jsonPath("$.data.items[0].integratedSuggestion.text").value("현재 확인된 개선 사항이 없습니다."))
                .andExpect(jsonPath("$.data.items[0].cache.status").value("UNAVAILABLE"));
        assertThat(llm.calls.get()).isZero();
        assertThat(aiRepository.count()).isZero();
    }

    @Test
    void 오류는_공통_봉투를_쓴다() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(req("{\"dayOfWeek\":1,\"startTime\":\"21:00\",\"endTime\":\"19:00\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_TIME_RANGE"))
                .andExpect(jsonPath("$.error.details[0].field").value("criteria.endTime"));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(req("{\"dayOfWeek\":7,\"startTime\":\"19:00\",\"endTime\":\"21:00\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].field").value("criteria.dayOfWeek"));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(req("{\"dayOfWeek\":1,\"endTime\":\"21:00\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[?(@.field=='criteria.startTime')]").isNotEmpty());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(req("{\"facilityTypeIds\":[9999],\"dayOfWeek\":1,"
                                + "\"startTime\":\"19:00\",\"endTime\":\"21:00\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("criteria.facilityTypeIds"));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(req("{\"district\":\"서울\",\"dayOfWeek\":1,"
                                + "\"startTime\":\"19:00\",\"endTime\":\"21:00\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(post(URL).contentType(MediaType.TEXT_PLAIN).content(req(CRITERIA)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
        assertThat(llm.calls.get()).isZero();
    }

    /** 이 테스트 시설의 facilityId를 넣은 요청 본문 */
    private String req(String criteriaJson) {
        return "{\"facilityId\":\"" + facility.getFacilityId() + "\",\"criteria\":" + criteriaJson + "}";
    }

    private static String bodyWithFacilityId(String facilityIdJson) {
        return "{\"facilityId\":" + facilityIdJson
                + ",\"criteria\":{\"district\":\"기흥구\",\"dayOfWeek\":1,\"startTime\":\"19:00\",\"endTime\":\"21:00\"}}";
    }

    @Test
    void facilityId가_없거나_null이면_400_REQUIRED다() throws Exception {
        for (String body : new String[] { "{\"criteria\":" + CRITERIA + "}", bodyWithFacilityId("null") }) {
            mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.error.details[0].field").value("facilityId"))
                    .andExpect(jsonPath("$.error.details[0].reason").value("REQUIRED"))
                    .andExpect(jsonPath("$.error.requestId").isString());
        }
        assertThat(llm.calls.get()).isZero();
    }

    @Test
    void criteria_district가_시설과_달라도_그_시설을_분석한다() throws Exception {
        // criteria.district(기흥구)는 시설(수지구)과 달라도 후보 필터에 쓰지 않고, 응답 criteria에는 그대로 돌려준다
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithFacilityId("\"" + facility.getFacilityId() + "\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.criteria.district").value("기흥구"))
                .andExpect(jsonPath("$.data.candidateCount").value(1))
                .andExpect(jsonPath("$.data.items[0].facility.facilityId").value(String.valueOf(facility.getFacilityId())))
                .andExpect(jsonPath("$.data.items[0].cache.status").value("CREATED"));
    }

    @Test
    void facilityId_형식_오류는_400_없는_시설은_404다() throws Exception {
        for (String bad : new String[] { "\"abc\"", "\"\"", "\"9223372036854775808\"" }) {
            mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(bodyWithFacilityId(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.error.details[0].field").value("facilityId"));
        }
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(bodyWithFacilityId("\"999999\"")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FACILITY_NOT_FOUND"))
                .andExpect(jsonPath("$.error.requestId").isString());
        assertThat(llm.calls.get()).isZero();
    }
}
