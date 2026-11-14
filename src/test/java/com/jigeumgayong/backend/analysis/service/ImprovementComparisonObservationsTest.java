package com.jigeumgayong.backend.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jigeumgayong.backend.analysis.service.ImprovementObservations.Observation;
import com.jigeumgayong.backend.facility.service.PeerHoursComparison;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 동종 시설 비교(PEER_*)와 유동인구 비교(FLOW_*) 관찰을 모으고, 마감 비교를 하나로 묶는 규칙. DB·Spring 없이 확인한다. */
class ImprovementComparisonObservationsTest {

    static final int TUE = 1;
    static final int START = 19 * 60;
    static final int END = 21 * 60;

    /** 실제 seed CSV를 읽는다: 처인구 권장 마감 22:00, 19~21시는 붐비는 시간대 */
    private final FloatingPopulation flow = new FloatingPopulation();

    private static List<String> types(List<Observation> observations) {
        return observations.stream().map(Observation::type).toList();
    }

    private List<Observation> compare(boolean peerEnabled, boolean flowEnabled, PeerHoursComparison.Result peers,
                                      Integer ownClose, boolean ownOpen) {
        return compare("도서관", peerEnabled, flowEnabled, peers, ownClose, ownOpen);
    }

    private List<Observation> compare(String typeName, boolean peerEnabled, boolean flowEnabled,
                                      PeerHoursComparison.Result peers, Integer ownClose, boolean ownOpen) {
        return ImprovementObservations.comparisonObservations(peerEnabled, flowEnabled, peers, flow, typeName, "처인구",
                ownClose, ownOpen, null, TUE, START, END);
    }

    @Test
    void 유동인구_비교는_도서관_생활체육시설_문화센터에만_적용한다() {
        PeerHoursComparison.Result peers = new PeerHoursComparison.Result(3, 0, 3, 22 * 60); // 동종 비교는 하지 않는 크기
        for (String type : new String[] { "도서관", "생활체육시설", "문화센터" }) {
            assertThat(types(compare(type, true, true, peers, 18 * 60, true))).as(type)
                    .containsExactly(ImprovementObservations.FLOW_CLOSING);
        }
        for (String type : new String[] { "주민자치센터", "보건소", "평생학습관" }) {
            assertThat(compare(type, true, true, peers, 18 * 60, true)).as(type).isEmpty();
        }
    }

    @Test
    void 대상이_아닌_유형도_동종_시설_비교는_그대로_한다() {
        List<Observation> out = compare("주민자치센터", true, true, new PeerHoursComparison.Result(6, 6, 6, 22 * 60),
                18 * 60, true);

        assertThat(types(out)).containsExactly(ImprovementObservations.PEER_LATER_CLOSING);
    }

    @Test
    void 두_마감_비교가_함께_참이면_하나의_관찰로_묶는다() {
        // 비교 시설 6곳 모두 18시에 마감해 희망 시간에 운영하지 않고 마감 중앙값은 22:00, 이 시설은 18:00 마감
        List<Observation> out = compare(true, true, new PeerHoursComparison.Result(6, 0, 6, 22 * 60), 18 * 60, false);

        assertThat(types(out)).containsExactly(ImprovementObservations.COMPARED_CLOSING,
                ImprovementObservations.FLOW_SUPPLY_GAP);
        String merged = out.get(0).detail();
        assertThat(merged).contains("같은 행정구의 같은 유형 시설들은 화요일 마감 시각의 중앙값이 22:00")
                .contains("경기도 처인구 유동인구 통계에서도 22:00까지 유동 인구가 많은 시간대")
                .contains("이 시설은 18:00에 마감함");
        assertThat(types(out)).doesNotContain(ImprovementObservations.PEER_LATER_CLOSING,
                ImprovementObservations.FLOW_CLOSING);
    }

    @Test
    void 유동인구_쪽만_참이면_유동인구_마감_관찰만_나온다() {
        // 비교 시설이 5곳 미만이라 동종 시설 비교는 하지 않는다
        List<Observation> out = compare(true, true, new PeerHoursComparison.Result(3, 0, 3, 22 * 60), 18 * 60, true);

        assertThat(types(out)).containsExactly(ImprovementObservations.FLOW_CLOSING);
        assertThat(out.get(0).detail()).startsWith("경기도 처인구 유동인구 통계");
    }

    @Test
    void 동종_시설_쪽만_참이면_동종_시설_마감_관찰만_나온다() {
        // 유동인구 비교를 끈 경우
        List<Observation> out = compare(true, false, new PeerHoursComparison.Result(6, 6, 6, 22 * 60), 18 * 60, true);

        assertThat(types(out)).containsExactly(ImprovementObservations.PEER_LATER_CLOSING);
    }

    @Test
    void 마감_차이가_60분_미만이면_묶지_않고_각각_판단한다() {
        // 비교 시설 중앙값은 18:30이라 동종 시설 비교는 거짓, 유동인구 권장 마감 22:00과는 4시간 차이
        List<Observation> out = compare(true, true, new PeerHoursComparison.Result(6, 6, 6, 18 * 60 + 30), 18 * 60,
                true);

        assertThat(types(out)).containsExactly(ImprovementObservations.FLOW_CLOSING);
    }

    @Test
    void 둘_다_꺼져_있으면_관찰이_없다() {
        assertThat(compare(false, false, new PeerHoursComparison.Result(6, 0, 6, 22 * 60), 18 * 60, false)).isEmpty();
    }

    @Test
    void 이미_늦게까지_운영하면_마감_관찰은_없다() {
        List<Observation> out = compare(true, true, new PeerHoursComparison.Result(6, 6, 6, 22 * 60), 22 * 60, true);

        assertThat(out).isEmpty();
    }
}
