package com.smartcollab.global.util;

import com.smartcollab.global.security.SecurityConfig;
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
 *   <li>앱 CSP 에 {@code sandbox} 를 더해, 파일 문서가 브라우저에서 열려도 스크립트를 실행하지 못하고 앱과 다른 출처로
 *       취급되게 합니다 [S-13]. PDF 는 제외합니다 — 브라우저 내장 PDF 뷰어가 sandbox 문서에서 막히면 미리보기가 빈 화면이
 *       되는데, 이 환경에서는 Chromium 만 확인할 수 있었습니다(Chromium 152 는 sandbox 에서도 정상 표시).
 *       PDF 안의 스크립트는 브라우저 뷰어가 페이지 DOM 과 분리해 실행합니다.</li>
 * </ul>
 */
public final class FileResponses {

    static final String SANDBOXED_CSP = SecurityConfig.CSP + "; sandbox";

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
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (!(inline && kind == FileNames.PreviewKind.PDF)) {
            // 이 헤더가 있으면 Spring Security 는 앱 CSP 를 따로 쓰지 않으므로, 앱 CSP 전체에 sandbox 를 더해 보냅니다.
            response.header("Content-Security-Policy", SANDBOXED_CSP);
        }
        return response
                .contentType(type)
                .contentLength(size)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(in));
    }
}
