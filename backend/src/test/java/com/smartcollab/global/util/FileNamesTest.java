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
