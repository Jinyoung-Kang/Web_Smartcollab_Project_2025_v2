package com.smartcollab.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 읽히는 바이트 수와 SHA-256 을 동시에 계산합니다. 파일을 한 번만 읽으면서 무결성 해시를 얻기 위함입니다.
 */
final class HashingInputStream extends FilterInputStream {

    private final MessageDigest digest;
    private long count;

    HashingInputStream(InputStream in) {
        super(in);
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            digest.update((byte) b);
            count++;
        }
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n = super.read(buf, off, len);
        if (n > 0) {
            digest.update(buf, off, n);
            count += n;
        }
        return n;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    long count() {
        return count;
    }

    String sha256Hex() {
        return HexFormat.of().formatHex(digest.digest());
    }
}
