package com.smartcollab.folder;

import java.time.Instant;

/** 폴더 목록 한 줄에 필요한 하위 폴더 열만 [IMP-02] */
public record ListedFolder(Long id, String name, String ownerName, Instant createdAt) {
}
