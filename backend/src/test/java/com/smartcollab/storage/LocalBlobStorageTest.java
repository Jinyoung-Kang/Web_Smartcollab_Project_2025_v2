package com.smartcollab.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalBlobStorageTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("저장하면서 크기와 SHA-256 을 계산하고, 복사·삭제는 멱등")
    void putOpenCopyDelete() throws Exception {
        LocalBlobStorage storage = new LocalBlobStorage(root);
        byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
        StoredBlob blob = storage.put("files/a", new ByteArrayInputStream(bytes), bytes.length);
        assertThat(blob.size()).isEqualTo(3);
        assertThat(blob.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

        storage.copy("files/a", "files/b");
        try (InputStream in = storage.open("files/b")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("abc");
        }
        storage.delete("files/a");
        storage.delete("files/a");
        assertThatThrownBy(() -> storage.open("files/a")).isInstanceOf(BlobNotFoundException.class);
        assertThat(storage.readOnlyUrl("files/b", java.time.Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    @DisplayName("루트 밖을 가리키는 키는 거부한다 (경로 조작 방지)")
    void rejectsTraversal() {
        LocalBlobStorage storage = new LocalBlobStorage(root);
        assertThatThrownBy(() -> storage.resolve("../outside")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.resolve("files/../../x")).isInstanceOf(IllegalArgumentException.class);
    }
}
