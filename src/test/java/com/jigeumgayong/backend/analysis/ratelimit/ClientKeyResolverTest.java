package com.jigeumgayong.backend.analysis.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientKeyResolverTest {

    static final String UUID_UPPER = "3F2504E0-4F89-41D3-9A0C-0305E82C3301";
    static final String UUID_LOWER = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

    @Test
    void UUID_형식이면_소문자로_정규화해_쓴다() {
        ClientKey key = ClientKeyResolver.resolve(UUID_UPPER, "203.0.113.5", null);

        assertThat(key.clientId()).isEqualTo(UUID_LOWER);
    }

    @Test
    void UUID_형식이_아니면_무시한다() {
        assertThat(ClientKeyResolver.resolve("not-a-uuid", "203.0.113.5", null).clientId()).isNull();
        assertThat(ClientKeyResolver.resolve("", "203.0.113.5", null).clientId()).isNull();
        assertThat(ClientKeyResolver.resolve(null, "203.0.113.5", null).clientId()).isNull();
        assertThat(ClientKeyResolver.resolve("x".repeat(500), "203.0.113.5", null).clientId()).isNull();
        assertThat(ClientKeyResolver.resolve(UUID_LOWER + "; DROP", "203.0.113.5", null).clientId()).isNull();
    }

    @Test
    void 루프백_nginx_뒤에서는_X_Real_IP를_쓴다() {
        assertThat(ClientKeyResolver.resolve(null, "127.0.0.1", "198.51.100.7").ip()).isEqualTo("198.51.100.7");
        assertThat(ClientKeyResolver.resolve(null, "::1", "198.51.100.7").ip()).isEqualTo("198.51.100.7");
        assertThat(ClientKeyResolver.resolve(null, "0:0:0:0:0:0:0:1", "198.51.100.7").ip()).isEqualTo("198.51.100.7");
    }

    @Test
    void 직접_접속이면_헤더를_믿지_않고_원격_주소를_쓴다() {
        // nginx를 거치지 않은 요청이 X-Real-IP를 위조하는 경우
        assertThat(ClientKeyResolver.resolve(null, "203.0.113.5", "1.2.3.4").ip()).isEqualTo("203.0.113.5");
    }

    @Test
    void X_Real_IP가_IP가_아니면_루프백_주소로_둔다() {
        assertThat(ClientKeyResolver.resolve(null, "127.0.0.1", "evil.example.com").ip()).isEqualTo("127.0.0.1");
        assertThat(ClientKeyResolver.resolve(null, "127.0.0.1", "999.1.1.1").ip()).isEqualTo("127.0.0.1");
        assertThat(ClientKeyResolver.resolve(null, "127.0.0.1", "1.2.3.4, 5.6.7.8").ip()).isEqualTo("127.0.0.1");
        assertThat(ClientKeyResolver.resolve(null, "127.0.0.1", "").ip()).isEqualTo("127.0.0.1");
    }

    @Test
    void X_Forwarded_For는_읽지_않는다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "6.6.6.6"); // 클라이언트가 보낸 값은 위조 가능
        request.addHeader("X-Real-IP", "198.51.100.7");

        assertThat(ClientKeyResolver.resolve(request).ip()).isEqualTo("198.51.100.7");
    }

    @Test
    void IPv6는_같은_64비트_앞부분을_한_사람으로_묶는다() {
        String a = ClientKeyResolver.resolve(null, "127.0.0.1", "2001:db8:1:2:aaaa:bbbb:cccc:dddd").ip();
        String b = ClientKeyResolver.resolve(null, "127.0.0.1", "2001:db8:1:2::1").ip();
        String other = ClientKeyResolver.resolve(null, "127.0.0.1", "2001:db8:1:3::1").ip();

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(other);
    }

    @Test
    void IPv4_매핑된_IPv6는_IPv4로_본다() {
        assertThat(ClientKeyResolver.resolve(null, "127.0.0.1", "::ffff:1.2.3.4").ip()).isEqualTo("1.2.3.4");
    }

    @Test
    void 원격_주소가_없으면_unknown() {
        assertThat(ClientKeyResolver.resolve(null, null, null).ip()).isEqualTo("unknown");
    }

    @Test
    void 요청_객체에서_UUID와_IP를_함께_꺼낸다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Client-Id", UUID_UPPER);
        request.addHeader("X-Real-IP", "198.51.100.7");

        ClientKey key = ClientKeyResolver.resolve(request);

        assertThat(key.clientId()).isEqualTo(UUID_LOWER);
        assertThat(key.ip()).isEqualTo("198.51.100.7");
    }

    @Test
    void 로그에_찍어도_IP와_UUID_원문이_드러나지_않는다() {
        ClientKey key = new ClientKey(UUID_LOWER, "198.51.100.7");

        assertThat(key.toString()).doesNotContain("198.51.100.7").doesNotContain(UUID_LOWER);
    }
}
