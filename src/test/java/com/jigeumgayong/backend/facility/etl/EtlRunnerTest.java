package com.jigeumgayong.backend.facility.etl;

import static org.assertj.core.api.Assertions.assertThat;

import com.jigeumgayong.backend.facility.repository.FacilityRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 플래그를 켜고 앱을 기동하면 Runner가 적재하고 격리 목록을 CSV로 남기는지 확인한다 (local 프로파일, H2). */
@SpringBootTest(properties = {
        "app.etl.enabled=true",
        "app.etl.quarantine-file=build/etl-test/quarantine.csv"
})
class EtlRunnerTest {

    @Autowired FacilityRepository facilityRepository;

    @Test
    void 기동하면_적재되고_격리_목록이_파일로_남는다() throws IOException {
        assertThat(facilityRepository.count()).isEqualTo(348);

        List<String> lines = Files.readAllLines(Path.of("build/etl-test/quarantine.csv"));
        assertThat(lines.get(0)).isEqualTo("source_type,source_key,name,address,reason");
        assertThat(lines).hasSize(3); // 헤더 + 격리 2건
    }
}
