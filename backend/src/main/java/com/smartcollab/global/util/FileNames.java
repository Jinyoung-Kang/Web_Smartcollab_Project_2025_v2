package com.smartcollab.global.util;

import com.smartcollab.global.error.ApiException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 파일·폴더 이름 검증과 확장자 분류.
 */
public final class FileNames {

    public static final int MAX_LENGTH = 255;

    private static final Set<String> IMAGE = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");
    private static final Set<String> TEXT = Set.of("txt", "md", "log", "csv", "json");
    private static final Set<String> OFFICE = Set.of("docx", "xlsx", "pptx", "doc", "xls", "ppt");

    /**
     * 글자 방향을 바꾸거나 보이지 않는 문자 [S-12]. "report" + U+202E + "fdp.exe" 가 "reportexe.pdf" 처럼 보이는
     * 확장자 위장을 막습니다. 이모지 결합(ZWJ U+200D)과 페르시아어·인도 문자 등에 필요한 ZWNJ(U+200C)는 그대로 둡니다.
     * (소스에 실제 문자가 들어가지 않도록 정규식 이스케이프로 적습니다.)
     */
    private static final Pattern BIDI_OR_INVISIBLE =
            Pattern.compile("[\\u061C\\u200B\\u200E\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]");

    private FileNames() {
    }

    /**
     * 업로드된 파일 이름을 정리합니다. 경로 구분자 앞부분(일부 브라우저가 보내는 전체 경로)과 제어 문자,
     * 방향 제어·보이지 않는 문자를 제거합니다.
     */
    public static String sanitizeUploadName(String raw) {
        if (raw == null) {
            throw ApiException.badRequest("파일 이름이 비어 있습니다.");
        }
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return validate(BIDI_OR_INVISIBLE.matcher(name.replaceAll("\\p{Cntrl}", "")).replaceAll(""));
    }

    /**
     * 사용자가 입력한 이름(새 폴더·이름 변경)을 검증합니다.
     */
    public static String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("이름을 입력하세요.");
        }
        String name = raw.strip();
        if (name.equals(".") || name.equals("..")) {
            throw ApiException.badRequest("사용할 수 없는 이름입니다.");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("이름에 / \\ 또는 제어 문자를 쓸 수 없습니다.");
        }
        if (BIDI_OR_INVISIBLE.matcher(name).find()) {
            throw ApiException.badRequest("이름에 글자 방향 제어 문자나 보이지 않는 문자를 쓸 수 없습니다.");
        }
        if (name.length() > MAX_LENGTH) {
            throw ApiException.badRequest("이름은 " + MAX_LENGTH + "자 이하여야 합니다.");
        }
        return name;
    }

    public static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static PreviewKind previewKind(String name) {
        String ext = extension(name);
        if (IMAGE.contains(ext)) return PreviewKind.IMAGE;
        if (ext.equals("pdf")) return PreviewKind.PDF;
        if (TEXT.contains(ext)) return PreviewKind.TEXT;
        if (OFFICE.contains(ext)) return PreviewKind.OFFICE;
        return PreviewKind.NONE;
    }

    public static boolean isTextEditable(String name) {
        return previewKind(name) == PreviewKind.TEXT;
    }

    public enum PreviewKind {IMAGE, PDF, TEXT, OFFICE, NONE}
}
