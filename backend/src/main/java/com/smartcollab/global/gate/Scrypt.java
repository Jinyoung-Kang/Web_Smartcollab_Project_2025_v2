package com.smartcollab.global.gate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * scrypt 키 유도 함수 (RFC 7914). 접근 코드 해시를 확인하는 데만 씁니다 [GATE-01].
 * <p>JDK 에는 scrypt 가 없어 직접 구현했습니다. 이 기능 하나를 위해 암호 라이브러리(BouncyCastle, 약 8MB)를 더하지 않으려는 것이고,
 * 결과는 RFC 7914 의 시험 벡터와 Python {@code hashlib.scrypt}(OpenSSL)의 출력으로 확인합니다({@code ScryptTest}).
 * 사용자 비밀번호는 여기를 쓰지 않습니다(BCrypt).</p>
 */
final class Scrypt {

    /** 한 번 계산에 쓰는 메모리(128·r·N 바이트) 상한 — 설정 실수로 서버 메모리를 다 쓰지 않게 합니다. */
    static final long MAX_MEMORY_BYTES = 64L * 1024 * 1024;

    private Scrypt() {
    }

    static byte[] derive(byte[] password, byte[] salt, int n, int r, int p, int dkLen) {
        if (n < 2 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("scrypt N 은 2 이상인 2의 거듭제곱이어야 합니다");
        }
        if (r < 1 || p < 1 || p > 16 || dkLen < 1 || dkLen > 64) {
            throw new IllegalArgumentException("scrypt r·p·길이가 허용 범위를 벗어났습니다");
        }
        if (128L * r * n > MAX_MEMORY_BYTES) {
            throw new IllegalArgumentException("scrypt 메모리(128·r·N)가 상한을 넘습니다");
        }
        int blockBytes = 128 * r;
        byte[] b = pbkdf2Sha256(password, salt, p * blockBytes);
        int[] x = new int[32 * r];
        int[] y = new int[32 * r];
        int[] v = new int[32 * r * n];
        for (int i = 0; i < p; i++) {
            roMix(b, i * blockBytes, r, n, x, y, v);
        }
        return pbkdf2Sha256(password, b, dkLen);
    }

    /** PBKDF2-HMAC-SHA256, 반복 1회 (scrypt 가 쓰는 형태) */
    private static byte[] pbkdf2Sha256(byte[] password, byte[] salt, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password, "HmacSHA256"));
            byte[] out = new byte[length];
            for (int block = 1, offset = 0; offset < length; block++, offset += 32) {
                mac.update(salt);
                mac.update(new byte[]{(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block});
                byte[] u = mac.doFinal();
                System.arraycopy(u, 0, out, offset, Math.min(32, length - offset));
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 을 쓸 수 없습니다", e);
        }
    }

    private static void roMix(byte[] b, int offset, int r, int n, int[] x, int[] y, int[] v) {
        int words = 32 * r;
        for (int i = 0; i < words; i++) {
            x[i] = littleEndian(b, offset + i * 4);
        }
        for (int i = 0; i < n; i++) {
            System.arraycopy(x, 0, v, i * words, words);
            blockMix(x, y, r);
        }
        for (int i = 0; i < n; i++) {
            int j = x[(2 * r - 1) * 16] & (n - 1);   // Integerify: 마지막 64바이트 블록의 첫 단어 (N ≤ 2^31)
            for (int k = 0; k < words; k++) {
                x[k] ^= v[j * words + k];
            }
            blockMix(x, y, r);
        }
        for (int i = 0; i < words; i++) {
            int w = x[i];
            int at = offset + i * 4;
            b[at] = (byte) w;
            b[at + 1] = (byte) (w >>> 8);
            b[at + 2] = (byte) (w >>> 16);
            b[at + 3] = (byte) (w >>> 24);
        }
    }

    /** BlockMix(Salsa20/8): 결과는 (Y0, Y2, …, Y1, Y3, …) 순서로 b 에 다시 씁니다. */
    private static void blockMix(int[] b, int[] y, int r) {
        int[] t = new int[16];
        System.arraycopy(b, (2 * r - 1) * 16, t, 0, 16);
        for (int i = 0; i < 2 * r; i++) {
            for (int k = 0; k < 16; k++) {
                t[k] ^= b[i * 16 + k];
            }
            salsa20_8(t);
            System.arraycopy(t, 0, y, i * 16, 16);
        }
        for (int i = 0; i < r; i++) {
            System.arraycopy(y, (2 * i) * 16, b, i * 16, 16);
            System.arraycopy(y, (2 * i + 1) * 16, b, (r + i) * 16, 16);
        }
    }

    private static void salsa20_8(int[] b) {
        int[] x = b.clone();
        for (int i = 8; i > 0; i -= 2) {
            x[4] ^= rotl(x[0] + x[12], 7);   x[8] ^= rotl(x[4] + x[0], 9);
            x[12] ^= rotl(x[8] + x[4], 13);  x[0] ^= rotl(x[12] + x[8], 18);
            x[9] ^= rotl(x[5] + x[1], 7);    x[13] ^= rotl(x[9] + x[5], 9);
            x[1] ^= rotl(x[13] + x[9], 13);  x[5] ^= rotl(x[1] + x[13], 18);
            x[14] ^= rotl(x[10] + x[6], 7);  x[2] ^= rotl(x[14] + x[10], 9);
            x[6] ^= rotl(x[2] + x[14], 13);  x[10] ^= rotl(x[6] + x[2], 18);
            x[3] ^= rotl(x[15] + x[11], 7);  x[7] ^= rotl(x[3] + x[15], 9);
            x[11] ^= rotl(x[7] + x[3], 13);  x[15] ^= rotl(x[11] + x[7], 18);
            x[1] ^= rotl(x[0] + x[3], 7);    x[2] ^= rotl(x[1] + x[0], 9);
            x[3] ^= rotl(x[2] + x[1], 13);   x[0] ^= rotl(x[3] + x[2], 18);
            x[6] ^= rotl(x[5] + x[4], 7);    x[7] ^= rotl(x[6] + x[5], 9);
            x[4] ^= rotl(x[7] + x[6], 13);   x[5] ^= rotl(x[4] + x[7], 18);
            x[11] ^= rotl(x[10] + x[9], 7);  x[8] ^= rotl(x[11] + x[10], 9);
            x[9] ^= rotl(x[8] + x[11], 13);  x[10] ^= rotl(x[9] + x[8], 18);
            x[12] ^= rotl(x[15] + x[14], 7); x[13] ^= rotl(x[12] + x[15], 9);
            x[14] ^= rotl(x[13] + x[12], 13); x[15] ^= rotl(x[14] + x[13], 18);
        }
        for (int i = 0; i < 16; i++) {
            b[i] += x[i];
        }
    }

    private static int rotl(int value, int bits) {
        return Integer.rotateLeft(value, bits);
    }

    private static int littleEndian(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }
}
