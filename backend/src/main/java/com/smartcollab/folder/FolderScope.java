package com.smartcollab.folder;

/** 폴더가 속한 저장 공간: 팀 폴더면 teamId, 개인 폴더면 ownerId 만 의미가 있습니다. */
public record FolderScope(Long ownerId, Long teamId) {
}
