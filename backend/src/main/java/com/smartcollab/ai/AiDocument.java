package com.smartcollab.ai;

/**
 * AI 에 보낼 문서. 텍스트(텍스트 문서·Word 에서 뽑은 본문) 또는 PDF 원본입니다 [AI-02].
 * 문서 내용은 사용자가 올린 신뢰할 수 없는 데이터라, 지시는 시스템 프롬프트에만 두고 문서는 document 블록으로 따로 보냅니다.
 */
public record AiDocument(String title, String text, byte[] pdf) {

    public static AiDocument text(String title, String text) {
        return new AiDocument(title, text, null);
    }

    public static AiDocument pdf(String title, byte[] pdf) {
        return new AiDocument(title, null, pdf);
    }

    public boolean isPdf() {
        return pdf != null;
    }
}
