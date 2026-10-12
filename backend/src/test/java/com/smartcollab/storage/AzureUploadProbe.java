package com.smartcollab.storage;

import java.io.InputStream;
import java.util.UUID;

/**
 * [H-1] 작은 힙의 자식 JVM 에서 운영과 같은 Azure 클라이언트({@link StorageConfig#azure})로 큰 파일을 올립니다.
 * 인자: 연결 문자열, 컨테이너, 바이트 수. 성공하면 "OK 바이트수", 힙이 모자라면 OutOfMemoryError 로 끝납니다.
 */
public final class AzureUploadProbe {

    private AzureUploadProbe() {
    }

    public static void main(String[] args) {
        AzureBlobStorage storage = StorageConfig.azure(args[0], args[1]);
        long size = Long.parseLong(args[2]);
        StoredBlob blob = storage.put("probe/" + UUID.randomUUID(), new PatternStream(size), size);
        System.out.println("OK " + blob.size());
    }

    /** 메모리에 올리지 않고 만들어 내는 내용 */
    private static final class PatternStream extends InputStream {
        private final long size;
        private long position;

        PatternStream(long size) {
            this.size = size;
        }

        @Override
        public int read() {
            return position < size ? (int) (position++ * 31 & 0xff) : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (position >= size) {
                return -1;
            }
            int n = (int) Math.min(len, size - position);
            for (int i = 0; i < n; i++) {
                b[off + i] = (byte) (position++ * 31);
            }
            return n;
        }
    }
}
