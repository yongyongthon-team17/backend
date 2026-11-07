package com.jigeumgayong.backend.facility.service;

import com.jigeumgayong.backend.facility.domain.District;
import com.jigeumgayong.backend.facility.dto.FacilitySearchResponse.Criteria;
import com.jigeumgayong.backend.facility.repository.FacilityTypeRepository;
import com.jigeumgayong.backend.global.error.BusinessException;
import com.jigeumgayong.backend.global.error.ErrorCode;
import com.jigeumgayong.backend.global.error.ErrorDetail;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A02·A04가 함께 쓰는 공통 Criteria(06 3.6) 검증·정규화.
 * 검증 순서: INVALID_REQUEST(요일 → district → typeIds) 먼저, 시간(INVALID_TIME_RANGE)은 그 다음.
 */
@Component
@RequiredArgsConstructor
public class SearchCriteriaNormalizer {

    private final FacilityTypeRepository typeRepository;

    /** 검증을 통과한 조건. startMin/endMin은 자정부터의 분. */
    public record Normalized(Criteria criteria, List<Short> typeIds, int startMin, int endMin,
                             String keywordPattern) {
    }

    public Normalized normalize(String keyword, String district, List<Integer> typeIds,
                                int dayOfWeek, String startText, String endText) {
        if (dayOfWeek < 0 || dayOfWeek > 6) {
            throw invalidRequest("dayOfWeek", ErrorDetail.OUT_OF_RANGE);
        }
        if (district != null && !District.isValid(district)) {
            throw invalidRequest("district", ErrorDetail.INVALID_VALUE);
        }
        List<Integer> distinctTypeIds = typeIds == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(typeIds));
        List<Short> shortTypeIds = validateTypeIds(distinctTypeIds);

        int[] range = TimeText.parseRange(startText, endText);
        String normalizedKeyword = normalizeKeyword(keyword);
        Criteria criteria = new Criteria(normalizedKeyword, district, distinctTypeIds, dayOfWeek,
                TimeText.format(range[0]), TimeText.format(range[1]));
        return new Normalized(criteria, shortTypeIds, range[0], range[1], likePattern(normalizedKeyword));
    }

    /** 존재하지 않는 typeId가 하나라도 있으면 400 (06 3.6). */
    private List<Short> validateTypeIds(List<Integer> typeIds) {
        List<Short> result = new ArrayList<>();
        for (Integer id : typeIds) {
            if (id == null || id <= 0 || id > Short.MAX_VALUE) {
                throw invalidRequest("facilityTypeIds", ErrorDetail.INVALID_VALUE);
            }
            result.add(id.shortValue());
        }
        if (!result.isEmpty()) {
            Set<Short> found = new LinkedHashSet<>();
            typeRepository.findAllById(result).forEach(t -> found.add(t.getTypeId()));
            if (found.size() != result.size()) {
                throw invalidRequest("facilityTypeIds", ErrorDetail.INVALID_VALUE);
            }
        }
        return result;
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** LIKE 와일드카드(%, _)와 이스케이프 문자(\)를 글자 그대로 검색하도록 이스케이프한다. */
    private static String likePattern(String keyword) {
        if (keyword == null) {
            return "%";
        }
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static BusinessException invalidRequest(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage(),
                List.of(new ErrorDetail(field, reason)));
    }
}
