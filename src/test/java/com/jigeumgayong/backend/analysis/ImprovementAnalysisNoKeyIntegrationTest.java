package com.jigeumgayong.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jigeumgayong.backend.analysis.llm.LlmClient;
import com.jigeumgayong.backend.analysis.llm.OpenAiLlmClient;
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
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 가짜 LLM 없이 실제 OpenAiLlmClient + 빈 API 키로 기동한다.
 * 서버가 죽지 않고 LLM 실패와 같은 경로(200 부분 응답)로 처리되는지 확인한다. 네트워크 호출은 나가지 않는다.
 */
@SpringBootTest(properties = "app.llm.api-key=")
@AutoConfigureMockMvc
class ImprovementAnalysisNoKeyIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired LlmClient llmClient;
    @Autowired AiAnalysisRepository aiRepository;
    @Autowired FacilityTypeRepository typeRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired FacilitySpaceRepository spaceRepository;
    @Autowired OperatingHourRepository hourRepository;
    @Autowired FacilityAmenityRepository amenityRepository;

    @BeforeEach
    void setUp() {
        aiRepository.deleteAll();
        amenityRepository.deleteAll();
        hourRepository.deleteAll();
        spaceRepository.deleteAll();
        facilityRepository.deleteAll();
        typeRepository.deleteAll();
        FacilityType type = typeRepository.save(FacilityType.builder().name("도서관").chipGroup(ChipGroup.LIBRARY).build());
        Facility f = facilityRepository.save(Facility.builder().type(type).sourceType(SourceType.MANUAL)
                .sourceKey("nokey").district("수지구").accessType(AccessType.OPEN_HOURS).name("시설").address("주소")
                .latitude(new BigDecimal("37.32")).longitude(new BigDecimal("127.1")).build());
        spaceRepository.save(new FacilitySpace(f.getFacilityId(), SpaceType.DEFAULT, "시설 전체"));
        hourRepository.save(OperatingHour.builder().facilityId(f.getFacilityId()).spaceType(SpaceType.DEFAULT)
                .dayOfWeek((short) 1).intervalSeq((short) 1).dayState(DayState.OPEN)
                .openMin((short) 540).closeMin((short) 1080).build());
    }

    @Test
    void 키가_없으면_서버는_정상이고_LLM_UNAVAILABLE_부분_응답이다() throws Exception {
        assertThat(llmClient).isInstanceOf(OpenAiLlmClient.class);

        mvc.perform(post("/api/v1/improvement-analyses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"criteria\":{\"dayOfWeek\":1,\"startTime\":\"19:00\",\"endTime\":\"21:00\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidateCount").value(1))
                .andExpect(jsonPath("$.data.items[0].proposalStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].cache.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.meta.warnings[?(@.code=='LLM_UNAVAILABLE')]").isNotEmpty());
        assertThat(aiRepository.count()).isZero();
    }
}
