package com.smartcollab.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ItemTransferServiceTest {

    @Test
    @DisplayName("같은 이름이 있으면 확장자 앞에 번호를 붙인다")
    void uniqueName() {
        Set<String> taken = new HashSet<>(Set.of("보고서.txt", "보고서 (1).txt", "폴더"));
        assertThat(ItemTransferService.uniqueName("보고서.txt", taken)).isEqualTo("보고서 (2).txt");
        assertThat(ItemTransferService.uniqueName("폴더", taken)).isEqualTo("폴더 (1)");
        assertThat(ItemTransferService.uniqueName("새 파일.md", taken)).isEqualTo("새 파일.md");
        assertThat(ItemTransferService.uniqueName(".env", taken)).isEqualTo(".env");
    }

    @Test
    @DisplayName("LIKE 와일드카드를 이스케이프한다")
    void escapeLike() {
        assertThat(FileService.escapeLike("100%_!")).isEqualTo("100!%!_!!");
    }
}
