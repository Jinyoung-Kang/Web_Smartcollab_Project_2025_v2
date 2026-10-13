package com.smartcollab.team;

/** 관리 화면의 소속 팀 한 줄 — 팀 이름과 팀장 여부만 (ADR-0015) */
public record UserTeam(Long userId, String teamName, boolean leader) {
}
