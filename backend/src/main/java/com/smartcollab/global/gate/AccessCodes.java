package com.smartcollab.global.gate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 이름 붙은 접근 코드 목록 — {@code GATE_CODES="이름:해시,이름:해시"} [GATE-01].
 * <p>해시 형식은 {@code scrypt.N.r.p.salt.hash}(salt·hash 는 패딩 없는 base64url)로, 포트폴리오의 다른 서비스 앞단(gate)과 같습니다.
 * 그래서 같은 명령으로 만든 해시를 그대로 옮겨 쓸 수 있습니다. 코드 원문은 서버에 두지 않습니다.
 * 코드마다 이름을 붙이면(예: 지원한 회사별) 누가 열었는지 로그로 알 수 있고, 하나씩 폐기할 수 있습니다.</p>
 */
public final class AccessCodes {

    static final int MAX_CODE_CHARS = 200;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final Map<String, Hash> codes;

    private AccessCodes(Map<String, Hash> codes) {
        this.codes = Collections.unmodifiableMap(codes);
    }

    /** @throws IllegalArgumentException 형식이 틀리면 — 무엇이 틀렸는지만 알리고 해시 값은 메시지에 넣지 않습니다 */
    public static AccessCodes parse(String raw) {
        Map<String, Hash> codes = new LinkedHashMap<>();
        for (String item : (raw == null ? "" : raw).split(",")) {
            String entry = item.strip();
            if (entry.isEmpty()) {
                continue;
            }
            int colon = entry.indexOf(':');
            String name = colon < 0 ? entry : entry.substring(0, colon);
            if (colon < 0 || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("GATE_CODES 항목은 '이름:해시' 형식이어야 합니다 (이름은 영문·숫자·-·_)");
            }
            if (codes.containsKey(name)) {
                throw new IllegalArgumentException("GATE_CODES 에 같은 이름이 두 번 있습니다: " + name);
            }
            codes.put(name, Hash.parse(name, entry.substring(colon + 1).strip()));
        }
        return new AccessCodes(codes);
    }

    public boolean isEmpty() {
        return codes.isEmpty();
    }

    public boolean contains(String name) {
        return codes.containsKey(name);
    }

    /** 맞는 코드의 이름. 코드가 여러 개면 모두 계산하므로 그만큼 느려집니다(무차별 대입도 함께 느려짐). */
    public Optional<String> match(String code) {
        if (code == null || code.isEmpty() || code.length() > MAX_CODE_CHARS) {
            return Optional.empty();
        }
        byte[] input = code.getBytes(StandardCharsets.UTF_8);
        return codes.entrySet().stream()
                .filter(e -> e.getValue().matches(input))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** 운영 문서·시험에서 해시를 만들 때 — 다른 서비스의 gate 와 같은 매개변수(N=2^14, r=8, p=1) */
    public static String hash(String code) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return hash(code, salt, 16384, 8, 1);
    }

    static String hash(String code, byte[] salt, int n, int r, int p) {
        byte[] digest = Scrypt.derive(code.getBytes(StandardCharsets.UTF_8), salt, n, r, p, 32);
        return "scrypt." + n + "." + r + "." + p + "." + B64.encodeToString(salt) + "." + B64.encodeToString(digest);
    }

    private record Hash(byte[] salt, byte[] digest, int n, int r, int p) {

        static Hash parse(String name, String encoded) {
            String[] parts = encoded.split("\\.");
            if (parts.length != 6 || !"scrypt".equals(parts[0])) {
                throw new IllegalArgumentException("GATE_CODES[" + name + "] 는 scrypt 해시(scrypt.N.r.p.salt.hash)여야 합니다");
            }
            try {
                Hash hash = new Hash(B64D.decode(parts[4]), B64D.decode(parts[5]),
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                // 매개변수가 허용 범위인지 기동할 때 확인합니다(요청마다 예외가 나지 않게)
                Scrypt.derive(new byte[]{0}, hash.salt, hash.n, hash.r, hash.p, hash.digest.length);
                return hash;
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("GATE_CODES[" + name + "] 해시를 읽을 수 없습니다: " + e.getMessage());
            }
        }

        boolean matches(byte[] code) {
            return MessageDigest.isEqual(Scrypt.derive(code, salt, n, r, p, digest.length), digest);
        }
    }
}
