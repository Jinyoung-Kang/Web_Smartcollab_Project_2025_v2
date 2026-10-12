package com.smartcollab.global.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [GATE-01] 직접 구현한 scrypt 를 다른 구현의 결과와 맞춰 봅니다. 기대값은 RFC 7914 §12 의 시험 벡터이고,
 * Python {@code hashlib.scrypt}(OpenSSL)로 다시 계산해 같은 값임을 확인했습니다. 접근 코드 해시는 포트폴리오의 다른 서비스
 * 잠금화면(gate.py)과 같은 명령으로 만든 값을 그대로 읽을 수 있어야 합니다.
 */
class ScryptTest {

    private static final byte[] SALT = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};

    @Test
    @DisplayName("[GATE-01] RFC 7914 시험 벡터와 같다 (N=1024, r=8, p=16)")
    void rfcVectorParallel() {
        byte[] out = Scrypt.derive(bytes("password"), bytes("NaCl"), 1024, 8, 16, 64);

        assertThat(HexFormat.of().formatHex(out)).isEqualTo(
                "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b373162"
                        + "2eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640");
    }

    @Test
    @DisplayName("[GATE-01] RFC 7914 시험 벡터와 같다 (N=16384, r=8, p=1 — 접근 코드 해시와 같은 매개변수)")
    void rfcVectorGateParameters() {
        byte[] out = Scrypt.derive(bytes("pleaseletmein"), bytes("SodiumChloride"), 16384, 8, 1, 64);

        assertThat(HexFormat.of().formatHex(out)).isEqualTo(
                "7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2"
                        + "d5432955613f0fcf62d49705242a9af9e61e85dc0d651e40dfcf017b45575887");
    }

    @Test
    @DisplayName("[GATE-01] 다른 서비스 잠금화면(Python hashlib)으로 만든 해시를 읽고, 맞는 코드만 통과시킨다")
    void readsHashesMadeByPythonGate() {
        // python: "scrypt.16384.8.1." + b64(salt) + "." + b64(hashlib.scrypt(code, salt=salt, n=16384, r=8, p=1, dklen=32))
        AccessCodes codes = AccessCodes.parse(
                "recruit:scrypt.16384.8.1.AAECAwQFBgcICQoLDA0ODw.ioomkg5MfPszeb5EJJjCVhpqNZ-aJ8fnyxz6WRReSdI,"
                        + " korean:scrypt.1024.8.1.AAECAwQFBgcICQoLDA0ODw.tJfemhDdc34EcadjwLQcBOClGB5h5hiFKOwe0EO_ixQ");

        assertThat(codes.match("TEST-CODE-1234")).contains("recruit");
        assertThat(codes.match("접근코드-한글")).contains("korean");
        assertThat(codes.match("TEST-CODE-1235")).isEmpty();
        assertThat(codes.match("")).isEmpty();
        assertThat(codes.match(null)).isEmpty();
    }

    @Test
    @DisplayName("[GATE-01] 같은 매개변수로 만든 해시는 Python 결과와 글자까지 같다")
    void hashFormatMatchesPython() {
        assertThat(AccessCodes.hash("TEST-CODE-1234", SALT, 16384, 8, 1))
                .isEqualTo("scrypt.16384.8.1.AAECAwQFBgcICQoLDA0ODw.ioomkg5MfPszeb5EJJjCVhpqNZ-aJ8fnyxz6WRReSdI");
        assertThat(AccessCodes.parse("a:" + AccessCodes.hash("새 코드")).match("새 코드")).contains("a");
    }

    @Test
    @DisplayName("[GATE-01] 형식이 틀린 GATE_CODES 는 기동할 때 거절하고, 메시지에 해시 값을 싣지 않는다")
    void rejectsMalformedCodes() {
        String hash = AccessCodes.hash("코드", SALT, 1024, 8, 1);

        assertThatThrownBy(() -> AccessCodes.parse(hash)).hasMessageContaining("'이름:해시'");
        assertThatThrownBy(() -> AccessCodes.parse("공백 이름:" + hash)).hasMessageContaining("'이름:해시'");
        assertThatThrownBy(() -> AccessCodes.parse("a:pbkdf2.1.2.3")).hasMessageContaining("scrypt 해시");
        assertThatThrownBy(() -> AccessCodes.parse("a:" + hash + ",a:" + hash)).hasMessageContaining("같은 이름");
        assertThatThrownBy(() -> AccessCodes.parse("a:scrypt.1000.8.1.AAEC.AAEC")).hasMessageContaining("2의 거듭제곱")
                .hasMessageNotContaining("AAEC");
        // 128·r·N 이 64MB 를 넘는 매개변수는 요청마다 서버 메모리를 크게 쓰므로 받지 않음
        assertThatThrownBy(() -> AccessCodes.parse("a:scrypt.1048576.8.1.AAEC.AAEC")).hasMessageContaining("상한");
        assertThatThrownBy(() -> AccessCodes.parse("a:scrypt.x.8.1.AAEC.AAEC")).isInstanceOf(IllegalArgumentException.class);
        assertThat(AccessCodes.parse(" , ").isEmpty()).isTrue();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
