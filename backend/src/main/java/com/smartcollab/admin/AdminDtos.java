package com.smartcollab.admin;

import java.time.Instant;
import java.util.List;

/** 관리 API 응답 (ADR-0015). 계정의 파일·폴더·채팅 정보는 담지 않습니다 — 이 형식이 관리자가 볼 수 있는 전부입니다. */
public final class AdminDtos {

    private AdminDtos() {
    }

    /**
     * @param storedBytes 개인 드라이브 사용량 (옛 버전·휴지통 포함, 저장 한도에 세는 값)
     * @param quotaBytes  개인 드라이브 저장 한도
     * @param demo        체험 계정 — 매일 초기화되므로 삭제할 수 없음
     */
    public record AccountRow(Long id, String username, String name, List<TeamRow> teams, long storedBytes, long quotaBytes,
                             Instant createdAt, boolean demo) {
    }

    public record TeamRow(String name, boolean leader) {
    }

    public record AccountPage(List<AccountRow> items, int page, int size, long totalElements, int totalPages) {
    }

    /**
     * @param transferredTeams 삭제한 계정이 팀장이던 팀 중 다른 멤버에게 팀장을 넘긴 팀 수
     * @param deletedTeams     넘겨받을 멤버가 없어 함께 삭제한 팀 수
     */
    public record DeletionResult(int transferredTeams, int deletedTeams) {
    }
}
