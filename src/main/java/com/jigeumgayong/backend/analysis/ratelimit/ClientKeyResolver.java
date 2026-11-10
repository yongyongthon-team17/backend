package com.jigeumgayong.backend.analysis.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 요청에서 {@link ClientKey}를 만든다.
 * <ul>
 *   <li>UUID: {@code X-Client-Id} 헤더가 UUID 형식일 때만 쓴다. 아니면 무시한다.</li>
 *   <li>IP: 서버 앞에 nginx가 있어 {@code getRemoteAddr()}는 루프백이다. <b>루프백에서 온 요청일 때만</b>
 *       nginx가 직접 채우는 {@code X-Real-IP}를 믿는다. {@code X-Forwarded-For}는 클라이언트가 위조할 수 있어
 *       읽지 않는다. 직접 접속(로컬 개발)이면 {@code getRemoteAddr()}를 그대로 쓴다.</li>
 * </ul>
 */
public final class ClientKeyResolver {

    public static final String CLIENT_ID_HEADER = "X-Client-Id";
    public static final String REAL_IP_HEADER = "X-Real-IP";

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private ClientKeyResolver() {
    }

    public static ClientKey resolve(HttpServletRequest request) {
        return resolve(request.getHeader(CLIENT_ID_HEADER), request.getRemoteAddr(), request.getHeader(REAL_IP_HEADER));
    }

    static ClientKey resolve(String clientIdHeader, String remoteAddr, String realIpHeader) {
        String clientId = clientIdHeader != null && UUID.matcher(clientIdHeader.trim()).matches()
                ? clientIdHeader.trim().toLowerCase(Locale.ROOT) : null;

        String ip = normalize(remoteAddr);
        if (isLoopback(remoteAddr)) {
            String real = normalize(realIpHeader);
            if (real != null) {
                ip = real;
            }
        }
        return new ClientKey(clientId, ip == null ? "unknown" : ip);
    }

    private static boolean isLoopback(String addr) {
        return "127.0.0.1".equals(addr) || "::1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr);
    }

    /** 유효한 IP 리터럴이 아니면 null. IPv6는 같은 /64를 한 사람으로 묶어 주소를 바꿔 우회하는 것을 줄인다. */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        var v4 = IPV4.matcher(s);
        if (v4.matches()) {
            for (int i = 1; i <= 4; i++) {
                if (Integer.parseInt(v4.group(i)) > 255) {
                    return null;
                }
            }
            return s;
        }
        if (s.indexOf(':') >= 0 && IPV6_CHARS.matcher(s).matches()) {
            try {
                // 문자가 IP 리터럴 문자뿐이라 DNS 조회 없이 파싱된다
                InetAddress addr = InetAddress.getByName(s);
                if (addr instanceof Inet4Address) {
                    return addr.getHostAddress(); // ::ffff:1.2.3.4 형태
                }
                if (addr instanceof Inet6Address) {
                    byte[] b = addr.getAddress();
                    return "v6:" + HexFormat.of().formatHex(b, 0, 8);
                }
            } catch (UnknownHostException e) {
                return null;
            }
        }
        return null;
    }
}
