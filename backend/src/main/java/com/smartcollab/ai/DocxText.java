package com.smartcollab.ai;

/**
 * Word(docx) 문서의 본문 텍스트 [AI-02].
 */
final class DocxText {

    private DocxText() {
    }

    static String extract(byte[] docx) {
        return extract(docx, 20L * 1024 * 1024);
    }

    static String extract(byte[] docx, long maxXmlBytes) {
        throw new UnsupportedOperationException("not implemented");
    }
}
