package com.jigeumgayong.backend.facility.etl;

import com.jigeumgayong.backend.facility.domain.ChipGroup;
import java.util.List;

/** 시설 유형 코드값 (docs/10 §2). type_id는 하드코딩하지 않고 name으로 조회한다. */
final class DefaultFacilityTypes {

    record Entry(String name, String chipGroup, String defaultImage) {
    }

    static final String LIBRARY = "도서관";
    static final String SPORTS = "생활체육시설";
    static final String ADMIN_CENTER = "주민자치센터";

    /** 도서관→도서관, 생활체육시설→체육시설, 평생학습관·문화센터→문화공간, 주민자치센터·보건소→복지시설 */
    static final List<Entry> ALL = List.of(
            new Entry(LIBRARY, ChipGroup.LIBRARY, "/images/default/library.png"),
            new Entry(SPORTS, ChipGroup.SPORTS, "/images/default/sports.png"),
            new Entry(ADMIN_CENTER, ChipGroup.WELFARE, "/images/default/admin_center.png"),
            new Entry("보건소", ChipGroup.WELFARE, "/images/default/health.png"),
            new Entry("평생학습관", ChipGroup.CULTURE, "/images/default/lifelong.png"),
            new Entry("문화센터", ChipGroup.CULTURE, "/images/default/culture.png"));

    private DefaultFacilityTypes() {
    }

    static boolean isKnown(String name) {
        return ALL.stream().anyMatch(e -> e.name().equals(name));
    }
}
