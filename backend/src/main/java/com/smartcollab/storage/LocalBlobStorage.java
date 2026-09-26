package com.smartcollab.storage;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

/**
 * 로컬 디스크 저장소 (개발·데모용). 임시 파일에 먼저 쓴 뒤 원자적으로 이동해 중간에 실패해도 반쯤 쓴 파일이 남지 않습니다.
 */
@Slf4j
public class LocalBlobStorage implements BlobStorage {

    private final Path root;

    public LocalBlobStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("저장소 디렉터리를 만들 수 없습니다: " + this.root, e);
        }
        log.info("Local blob storage: {}", this.root);
    }

    @Override
    public StoredBlob put(String key, InputStream content, long size) {
        Path target = resolve(key);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            HashingInputStream hashing = new HashingInputStream(content);
            Files.copy(hashing, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new StoredBlob(key, hashing.count(), hashing.sha256Hex());
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("파일을 저장하지 못했습니다.", e);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (NoSuchFileException e) {
            throw new BlobNotFoundException(key);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        Path target = resolve(targetKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(resolve(sourceKey), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (NoSuchFileException e) {
            throw new BlobNotFoundException(sourceKey);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<String> readOnlyUrl(String key, Duration ttl) {
        return Optional.empty();
    }

    @Override
    public String type() {
        return "local";
    }

    /** 키가 저장소 루트 밖을 가리키지 못하게 막습니다 (경로 조작 방지). */
    Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("잘못된 저장소 키입니다: " + key);
        }
        return path;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 임시 파일 정리 실패는 치명적이지 않음
        }
    }
}
