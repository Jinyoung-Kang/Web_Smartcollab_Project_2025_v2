package com.smartcollab.file;

import java.time.Instant;

/** 폴더 목록 한 줄에 필요한 파일 열만(엔티티를 만들지 않음) [IMP-02] */
public record ListedFile(Long id, String name, long size, String ownerName, Instant createdAt, Instant updatedAt) {
}
