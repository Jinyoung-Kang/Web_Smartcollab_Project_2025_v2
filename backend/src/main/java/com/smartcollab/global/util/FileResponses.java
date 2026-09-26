package com.smartcollab.global.util;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 파일 다운로드·미리보기 응답을 만듭니다.
 * <ul>
 *   <li>본문은 스트림으로 흘려보냅니다 (v1 은 파일 전체를 힙 메모리에 복사).</li>
 *   <li>파일명은 RFC 6266/5987 {@code filename*=UTF-8''...} 로 인코딩합니다 (v1 은 공백이 '+' 로 깨짐).</li>
 *   <li>브라우저 안에서 열어도 안전한 형식(이미지·PDF·텍스트)만 inline 으로 보내고, 텍스트는 text/plain 으로 고정합니다.
 *       HTML·SVG 등은 항상 첨부파일로 내려 저장형 XSS 를 막습니다.</li>
 * </ul>
 */
public final class FileResponses {

    private FileResponses() {
    }

    public static ResponseEntity<Resource> stream(InputStream in, long size, String filename, boolean inlineRequested) {
        FileNames.PreviewKind kind = FileNames.previewKind(filename);
        boolean inline = inlineRequested && (kind == FileNames.PreviewKind.IMAGE
                || kind == FileNames.PreviewKind.PDF || kind == FileNames.PreviewKind.TEXT);
        MediaType type = kind == FileNames.PreviewKind.TEXT
                ? new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8)
                : MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM);
        if (!inline) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(size)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(in));
    }
}
