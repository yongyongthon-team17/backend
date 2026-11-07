package com.jigeumgayong.backend.facility.etl;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.core.io.ClassPathResource;

/** 원천 CSV 읽기. 공공데이터 파일은 UTF-8(BOM 포함)과 CP949가 섞여 있어 UTF-8을 먼저 시도하고 실패하면 CP949로 읽는다. */
final class CsvReader {

    private static final String SEED_DIR = "seed/";

    private CsvReader() {
    }

    /** classpath:seed/{fileName}을 읽어 헤더 이름 → 값 맵의 목록으로 돌려준다. */
    static List<Map<String, String>> read(String fileName) {
        try {
            byte[] raw = new ClassPathResource(SEED_DIR + fileName).getInputStream().readAllBytes();
            return parse(decode(raw));
        } catch (IOException e) {
            throw new UncheckedIOException("CSV를 읽을 수 없음: " + fileName, e);
        }
    }

    static List<Map<String, String>> parse(String text) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build();
        List<Map<String, String>> rows = new ArrayList<>();
        try (CSVParser parser = format.parse(new StringReader(text))) {
            for (CSVRecord record : parser) {
                rows.add(record.toMap());
            }
        }
        return rows;
    }

    static String decode(byte[] raw) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            text = Charset.forName("MS949").decode(ByteBuffer.wrap(raw)).toString();
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
