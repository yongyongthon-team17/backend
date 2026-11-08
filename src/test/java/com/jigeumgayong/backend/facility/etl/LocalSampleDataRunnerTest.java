package com.jigeumgayong.backend.facility.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.jigeumgayong.backend.facility.domain.AccessType;
import com.jigeumgayong.backend.facility.domain.Facility;
import com.jigeumgayong.backend.facility.domain.SourceType;
import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import com.jigeumgayong.backend.facility.repository.OperatingHourRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.Transactional;

/** 로컬 샘플 적재 (ETL 이후 실행 순서, 멱등성, 샘플 필드, 스위치·프로파일 조건). */
class LocalSampleDataRunnerTest {

    /** ETL과 샘플을 모두 켠 기동. 속성 조합이 다른 테스트와 달라야 컨텍스트·DB가 섞이지 않는다. */
    @SpringBootTest(properties = {
            "app.etl.enabled=true",
            "app.sample-data.enabled=true",
            "app.etl.quarantine-file=build/etl-test/quarantine-sample-on.csv"
    })
    static class WithSamples {

        /** 샘플 리스너보다 먼저 실행되어, 그 시점에 ETL이 끝났고 샘플은 아직 없음을 기록한다. */
        static final List<Long> countAtReadyBeforeSamples = new ArrayList<>();

        @TestConfiguration
        static class Probe {
            @Autowired FacilityRepository repo;

            @EventListener(ApplicationReadyEvent.class)
            @Order(Ordered.HIGHEST_PRECEDENCE)
            void record() {
                countAtReadyBeforeSamples.add(repo.count());
            }
        }

        @Autowired FacilityRepository facilityRepository;
        @Autowired OperatingHourRepository hourRepository;
        @Autowired LocalSampleDataRunner runner;

        @Test
        void ETL이_끝난_뒤_샘플이_적재된다() {
            // ApplicationReadyEvent 시점에 ETL 348건은 이미 들어 있고(Runner 완료 후 발행), 샘플은 그 뒤에 들어간다
            assertThat(countAtReadyBeforeSamples).containsExactly(348L);
            assertThat(facilityRepository.count()).isEqualTo(351);
        }

        @Test
        @Transactional // 시설 유형이 LAZY라 세션이 필요하다
        void 샘플_세_건의_필드() {
            Facility always = sample("001");
            Facility reservation = sample("002");
            Facility noBus = sample("003");

            for (Facility f : List.of(always, reservation, noBus)) {
                assertThat(f.getSourceType()).isEqualTo(SourceType.MANUAL);
                assertThat(f.getName()).startsWith("[샘플]");
                assertThat(f.getAddress()).contains("샘플");
                assertThat(f.getLatitude().doubleValue()).isBetween(37.0, 37.5);
                assertThat(f.getLongitude().doubleValue()).isBetween(126.9, 127.5);
            }
            assertThat(always.getAccessType()).isEqualTo(AccessType.ALWAYS_OPEN);
            assertThat(always.getType().getName()).isEqualTo("생활체육시설");
            assertThat(always.getDistrict()).isEqualTo("수지구");
            assertThat(always.getBusStopCount()).isEqualTo(4);
            assertThat(hourRepository.findAllByFacilityId(always.getFacilityId())).isEmpty();

            assertThat(reservation.getAccessType()).isEqualTo(AccessType.RESERVATION);
            assertThat(reservation.getDistrict()).isEqualTo("기흥구");
            assertThat(reservation.getBusStopCount()).isEqualTo(2);
            // 요일 7 × 구간 1 = 7행
            assertThat(hourRepository.findAllByFacilityId(reservation.getFacilityId())).hasSize(7);

            assertThat(noBus.getAccessType()).isEqualTo(AccessType.OPEN_HOURS);
            assertThat(noBus.getType().getName()).isEqualTo("도서관");
            assertThat(noBus.getDistrict()).isEqualTo("처인구");
            assertThat(noBus.getBusStopCount()).isNull();
            assertThat(noBus.getBusCountedAt()).isNull();
        }

        @Test
        void 다시_실행해도_중복이_생기지_않는다() {
            runner.load();
            runner.load();
            assertThat(facilityRepository.count()).isEqualTo(351);
            assertThat(facilityRepository.findAll().stream()
                    .filter(f -> f.getSourceKey().startsWith("LOCAL-SAMPLE-")).count()).isEqualTo(3);
            assertThat(sample("003").getBusStopCount()).isNull();
        }

        private Facility sample(String no) {
            return facilityRepository.findBySourceTypeAndSourceKey(SourceType.MANUAL, "LOCAL-SAMPLE-" + no)
                    .orElseThrow();
        }
    }

    /** 샘플 스위치를 끄면 ETL 데이터만 들어간다. */
    @SpringBootTest(properties = {
            "app.etl.enabled=true",
            "app.sample-data.enabled=false",
            "app.etl.quarantine-file=build/etl-test/quarantine-sample-off.csv"
    })
    static class SwitchOff {

        @Autowired FacilityRepository facilityRepository;
        @Autowired(required = false) LocalSampleDataRunner runner;

        @Test
        void 스위치가_꺼지면_샘플이_없다() {
            assertThat(runner).isNull();
            assertThat(facilityRepository.count()).isEqualTo(348);
        }
    }

    /** 빈 생성 조건: local 프로파일 + 스위치 on 일 때만. prod에는 만들어지지 않는다. */
    static class Conditions {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(LocalSampleDataRunner.class)
                .withBean(FacilityLoader.class, () -> mock(FacilityLoader.class));

        @Test
        void prod_프로파일에서는_스위치가_켜져도_만들어지지_않는다() {
            runner.withPropertyValues("spring.profiles.active=prod", "app.sample-data.enabled=true")
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(LocalSampleDataRunner.class));
        }

        @Test
        void local_프로파일에서_스위치가_꺼지면_만들어지지_않는다() {
            runner.withPropertyValues("spring.profiles.active=local", "app.sample-data.enabled=false")
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(LocalSampleDataRunner.class));
        }

        @Test
        void local_프로파일에서_스위치가_켜지면_만들어진다() {
            runner.withPropertyValues("spring.profiles.active=local", "app.sample-data.enabled=true")
                    .run(ctx -> assertThat(ctx).hasSingleBean(LocalSampleDataRunner.class));
        }
    }
}
