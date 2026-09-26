package com.smartcollab.folder;

/**
 * 트리 구성·경로 계산용 경량 프로젝션 (엔티티 전체를 읽지 않음).
 */
public record FolderNode(Long id, String name, Long parentId) {
}
