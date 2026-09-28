package com.smartcollab.file;

/** 휴지통에 있는 폴더 트리 하나의 파일 수·크기 (휴지통 목록 표시용) [UX-06] */
public record TrashedTreeSize(Long rootId, long fileCount, long totalBytes) {
}
