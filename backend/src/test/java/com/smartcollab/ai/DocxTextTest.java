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

    @Test
    @DisplayName("[AI-02] 문단·탭·줄바꿈·표의 칸을 문서 순서대로 텍스트로 뽑는다")
    void extractsBodyText() {
        byte[] file = docx("<w:p><w:r><w:t>첫 문단</w:t></w:r><w:r><w:tab/><w:t xml:space=\"preserve\"> 이어서</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>둘째</w:t><w:br/><w:t>줄</w:t></w:r></w:p>"
                + "<w:tbl><w:tr><w:tc>" + paragraph("칸1") + "</w:tc><w:tc>" + paragraph("칸2") + "</w:tc></w:tr></w:tbl>");

        assertThat(DocxText.extract(file)).isEqualTo("첫 문단\t 이어서\n둘째\n줄\n칸1\n칸2");
    }

    @Test
    @DisplayName("[AI-02] DTD·외부 엔티티가 든 문서는 해석하지 않고 400 (서버 파일을 읽어 AI 로 보내는 XXE 차단)")
    void rejectsExternalEntities() {
        byte[] file = zip("word/document.xml", "<?xml version=\"1.0\"?><!DOCTYPE w:document [<!ENTITY x SYSTEM \"file:///etc/hosts\">]>"
                + "<w:document " + NS + "><w:body>" + paragraph("&x;") + "</w:body></w:document>");

        assertThatThrownBy(() -> DocxText.extract(file))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("[AI-02] 압축을 풀면 한도보다 큰 본문은 끝까지 풀지 않고 413 (압축 폭탄)")
    void stopsAtUncompressedLimit() {
        byte[] file = docx(paragraph(" ".repeat(3 * 1024 * 1024)));   // 3MB 공백 — 압축하면 수 KB
        assertThat(file.length).isLessThan(64 * 1024);

        assertThatThrownBy(() -> DocxText.extract(file, 1024 * 1024))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @Test
    @DisplayName("[AI-02] Word 본문이 없는 파일(손상·다른 형식)은 400")
    void rejectsNonDocx() {
        assertThatThrownBy(() -> DocxText.extract("not a zip".getBytes()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> DocxText.extract(zip("other.xml", "<a/>")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
