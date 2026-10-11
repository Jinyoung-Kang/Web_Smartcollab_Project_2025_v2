package com.smartcollab.ai;

import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.smartcollab.ai.DocxFixtures.NS;
import static com.smartcollab.ai.DocxFixtures.docx;
import static com.smartcollab.ai.DocxFixtures.paragraph;
import static com.smartcollab.ai.DocxFixtures.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [AI-02] Word(docx)는 AI 가 직접 읽지 못해 서버가 본문을 뽑아 보냅니다. 새 의존성(Apache POI) 없이 zip·XML 로 읽으므로,
 * 사용자가 올린 파일로 서버를 공격하는 경로(압축 폭탄·XML 외부 엔티티)를 함께 막는지 확인합니다.
 */
class DocxTextTest {

    private static final int LIMIT = 2 * 1024 * 1024;

    @Test
    @DisplayName("[AI-02] 문단·탭·줄바꿈·표의 칸을 문서 순서대로 텍스트로 뽑는다")
    void extractsBodyText() {
        byte[] file = docx("<w:p><w:r><w:t>첫 문단</w:t></w:r><w:r><w:tab/><w:t xml:space=\"preserve\"> 이어서</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>둘째</w:t><w:br/><w:t>줄</w:t></w:r></w:p>"
                + "<w:tbl><w:tr><w:tc>" + paragraph("칸1") + "</w:tc><w:tc>" + paragraph("칸2") + "</w:tc></w:tr></w:tbl>");

        assertThat(DocxText.extract(file, LIMIT)).isEqualTo("첫 문단\t 이어서\n둘째\n줄\n칸1\n칸2");
    }

    @Test
    @DisplayName("[AI-02] DTD·외부 엔티티가 든 문서는 해석하지 않고 400 (서버 파일을 읽어 AI 로 보내는 XXE 차단)")
    void rejectsExternalEntities() {
        byte[] file = zip("word/document.xml", "<?xml version=\"1.0\"?><!DOCTYPE w:document [<!ENTITY x SYSTEM \"file:///etc/hosts\">]>"
                + "<w:document " + NS + "><w:body>" + paragraph("&x;") + "</w:body></w:document>");

        assertThatThrownBy(() -> DocxText.extract(file, LIMIT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("[AI-02] 압축을 풀면 한도보다 큰 본문은 끝까지 풀지 않고 413 (압축 폭탄)")
    void stopsAtUncompressedLimit() {
        byte[] file = docx(paragraph(" ".repeat(3 * 1024 * 1024)));   // 3MB 공백 — 압축하면 수 KB
        assertThat(file.length).isLessThan(64 * 1024);

        assertThatThrownBy(() -> DocxText.extract(file, 1024 * 1024L, LIMIT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @Test
    @DisplayName("[AI-02] Word 본문이 없는 파일(손상·다른 형식)은 400")
    void rejectsNonDocx() {
        assertThatThrownBy(() -> DocxText.extract("not a zip".getBytes(), LIMIT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> DocxText.extract(zip("other.xml", "<a/>"), LIMIT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("[H-1] 뽑은 본문이 글자 수 상한을 넘으면 그 자리에서 멈추고 413 — 수십 KB 압축 파일이 2,100만 자로 풀려 추출 요약이 힙을 다 쓰던 문제")
    void limitsExtractedCharacters() {
        byte[] file = docx(paragraph("ab cd. ".repeat(200_000)));   // 140만 자, 압축하면 수 KB
        assertThat(file.length).isLessThan(64 * 1024);

        assertThatThrownBy(() -> DocxText.extract(file, 1_000_000))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
        assertThat(DocxText.extract(file, 1_500_000)).hasSize(1_400_000);
    }

    @Test
    @DisplayName("[H-1] 본문 앞의 다른 압축 항목도 풀어낸 크기를 합쳐 상한을 넘으면 413 — 건너뛰려고 끝까지 푸느라 요청마다 CPU 수 초")
    void limitsEntriesBeforeBody() throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bos)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("customXml/item1.xml"));
            zip.write(new byte[3 * 1024 * 1024]);   // 0 으로 3MB — 압축하면 수 KB
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write(("<w:document " + NS + "><w:body>" + paragraph("본문") + "</w:body></w:document>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThatThrownBy(() -> DocxText.extract(bos.toByteArray(), 1024 * 1024L, LIMIT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }
}
