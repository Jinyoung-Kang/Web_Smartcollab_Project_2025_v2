package com.smartcollab.global.util;

import com.smartcollab.global.error.ApiException;

import java.util.Locale;
import java.util.Set;

/**
 * 파일·폴더 이름 검증과 확장자 분류.
 */
public final class FileNames {

    public static final int MAX_LENGTH = 255;

    private static final Set<String> IMAGE = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");
    private static final Set<String> TEXT = Set.of("txt", "md", "log", "csv", "json");
    private static final Set<String> OFFICE = Set.of("docx", "xlsx", "pptx", "doc", "xls", "ppt");

    private FileNames() {
    }

    /**
     * 업로드된 파일 이름을 정리합니다. 경로 구분자 앞부분(일부 브라우저가 보내는 전체 경로)과 제어 문자를 제거합니다.
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
        return validate(name.replaceAll("\\p{Cntrl}", ""));
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
