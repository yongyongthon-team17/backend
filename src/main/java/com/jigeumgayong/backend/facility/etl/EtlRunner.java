package com.jigeumgayong.backend.facility.etl;

import com.jigeumgayong.backend.facility.etl.FacilityLoader.LoadResult;
import com.jigeumgayong.backend.facility.etl.SeedData.Quarantined;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 앱 기동 시 공공데이터를 적재한다 (B02). 기본은 꺼져 있고 {@code --app.etl.enabled=true}로 켠다.
 * 테스트·일반 기동에서는 실행되지 않는다. 격리된 행은 로그와 CSV 파일로 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.etl.enabled", havingValue = "true")
class EtlRunner implements ApplicationRunner {

    private final SeedBuilder seedBuilder;
    private final FacilityLoader loader;

    @Value("${app.etl.quarantine-file:build/etl/quarantine.csv}")
    private String quarantineFile;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        SeedData data = seedBuilder.build();
        data.notes().forEach((message, count) -> log.info("ETL 메모: {} = {}", message, count));
        for (Quarantined q : data.quarantine()) {
            log.warn("ETL 격리: {} {} {} ({})", q.sourceType(), q.sourceKey(), q.name(), q.reason());
        }
        writeQuarantine(data);

        LoadResult result = loader.load(data);
        log.info("ETL 완료: 시설 {}건(신규 {}, 갱신 {}), 공간 {}건, 운영시간 {}행, 새 편의시설 태그 {}건, 격리 {}건",
                data.facilities().size(), result.created(), result.updated(), result.spaces(),
                result.hourRows(), result.amenities(), data.quarantine().size());
        long withBus = data.facilities().stream().filter(f -> f.getBusStopCount() != null).count();
        log.info("ETL 교통: 정류장 수 계산 {}건, 미계산(NULL) {}건",
                withBus, data.facilities().size() - withBus);
    }

    private void writeQuarantine(SeedData data) throws IOException {
        Path path = Path.of(quarantineFile);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            printer.printRecord("source_type", "source_key", "name", "address", "reason");
            for (Quarantined q : data.quarantine()) {
                printer.printRecord(q.sourceType(), q.sourceKey(), q.name(), q.address(), q.reason());
            }
        }
    }
}
