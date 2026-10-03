package com.smartcollab.file;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 업로드할 파일 — 이름·크기·내용 [A-05]. 웹 요청(multipart)이든 체험 데이터든 같은 업로드 규칙을 쓰도록,
 * 업로드 서비스는 웹 타입(MultipartFile) 대신 이것을 받습니다.
 *
 * @param filename 사용자가 보낸 원래 이름(서비스가 정리·검증)
 * @param opener   내용을 여는 방법. 저장소에 쓸 때 한 번 엽니다.
 */
public record UploadSource(String filename, long size, Opener opener) {

    @FunctionalInterface
    public interface Opener {
        InputStream open() throws IOException;
    }

    public static UploadSource of(String filename, byte[] bytes) {
        return new UploadSource(filename, bytes.length, () -> new ByteArrayInputStream(bytes));
    }

    public boolean isEmpty() {
        return size == 0;
    }
}
