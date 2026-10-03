package com.smartcollab.global.util;

import com.smartcollab.global.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileNamesTest {

    @Test
    @DisplayName("업로드 파일명에서 경로와 제어 문자를 제거한다")
    void sanitize() {
        assertThat(FileNames.sanitizeUploadName("C:\\Users\\me\\report.pdf")).isEqualTo("report.pdf");
        assertThat(FileNames.sanitizeUploadName("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FileNames.sanitizeUploadName("a\u0000b.txt")).isEqualTo("ab.txt");
    }

    @Test
    @DisplayName("이름 검증: 빈 값·구분자·점 이름·길이 초과 거부")
    void validate() {
        assertThatThrownBy(() -> FileNames.validate(" ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FileNames.validate("a/b")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FileNames.validate("..")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FileNames.validate("x".repeat(256))).isInstanceOf(ApiException.class);
        assertThat(FileNames.validate("  보고서 (최종).md ")).isEqualTo("보고서 (최종).md");
    }

    @Test
    @DisplayName("[S-12] 방향 제어·보이지 않는 문자로 확장자를 위장할 수 없다 — 업로드는 지우고, 입력한 이름은 거절")
    void bidiAndInvisibleCharacters() {
        String spoofed = "report\u202Efdp.exe";   // 화면에는 "reportexe.pdf" 로 보임
        assertThat(FileNames.sanitizeUploadName(spoofed)).isEqualTo("reportfdp.exe");
        assertThat(FileNames.sanitizeUploadName("a\u2066b\u2069\u200B\uFEFF.txt")).isEqualTo("ab.txt");
        assertThatThrownBy(() -> FileNames.validate(spoofed))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("방향 제어");
        assertThatThrownBy(() -> FileNames.validate("a\u200Fb")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FileNames.sanitizeUploadName("\u202E\u200B")).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("[S-12] 이모지 결합(ZWJ)·페르시아어 등에 필요한 ZWNJ 는 그대로 둔다")
    void keepsJoinersNeededByScripts() {
        String family = "가족\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67.jpg";
        assertThat(FileNames.validate(family)).isEqualTo(family);
        assertThat(FileNames.sanitizeUploadName("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645.txt"))
                .isEqualTo("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645.txt");
    }

    @Test
    @DisplayName("확장자로 미리보기 종류를 판별한다")
    void previewKind() {
        assertThat(FileNames.previewKind("A.PNG")).isEqualTo(FileNames.PreviewKind.IMAGE);
        assertThat(FileNames.previewKind("x.pdf")).isEqualTo(FileNames.PreviewKind.PDF);
        assertThat(FileNames.previewKind("x.md")).isEqualTo(FileNames.PreviewKind.TEXT);
        assertThat(FileNames.previewKind("x.docx")).isEqualTo(FileNames.PreviewKind.OFFICE);
        assertThat(FileNames.previewKind("x.svg")).isEqualTo(FileNames.PreviewKind.NONE);
        assertThat(FileNames.extension("noext")).isEmpty();
        assertThat(FileNames.extension("trailing.")).isEmpty();
    }
}
