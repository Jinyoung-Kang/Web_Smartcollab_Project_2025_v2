package com.smartcollab.ai;

import java.util.Base64;

/**
 * AI 에 보낼 문서. 텍스트(텍스트 문서·Word 에서 뽑은 본문) 또는 PDF 원본입니다 [AI-02].
 * 문서 내용은 사용자가 올린 신뢰할 수 없는 데이터라, 지시는 시스템 프롬프트에만 두고 문서는 document 블록으로 따로 보냅니다.
 * PDF 는 만들 때 한 번만 base64 로 바꿔 둡니다 — 토큰 세기와 요약·번역 요청이 같은 문자열을 씁니다 [M-1].
 */
public record AiDocument(String title, String text, String pdfBase64) {

    public static AiDocument text(String title, String text) {
        return new AiDocument(title, text, null);
    }

    public static AiDocument pdf(String title, byte[] pdf) {
        return new AiDocument(title, null, Base64.getEncoder().encodeToString(pdf));
    }

    public boolean isPdf() {
        return pdfBase64 != null;
    }
}
