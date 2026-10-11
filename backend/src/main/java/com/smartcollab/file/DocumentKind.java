package com.smartcollab.file;

import com.smartcollab.global.util.FileNames;

/**
 * 요약·번역할 수 있는 문서 형식 [AI-02]. 텍스트는 그대로, PDF 는 원본을 AI 가 직접 읽고, Word(docx)는 서버에서 본문을 뽑아 보냅니다.
 * 옛 바이너리 형식(.doc)은 지원하지 않습니다.
 */
public enum DocumentKind {
    TEXT, PDF, DOCX;

    /** @return 지원하지 않는 형식이면 null */
    public static DocumentKind of(String fileName) {
        if (FileNames.isTextEditable(fileName)) return TEXT;
        return switch (FileNames.extension(fileName)) {
            case "pdf" -> PDF;
            case "docx" -> DOCX;
            default -> null;
        };
    }
}
