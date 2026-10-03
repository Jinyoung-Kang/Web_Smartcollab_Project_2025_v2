package com.smartcollab.team;

import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Teams", description = "팀·팀원·권한")
@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    @GetMapping
    public List<TeamDtos.TeamSummary> myTeams(@CurrentUser AuthUser user) {
        return teamService.myTeams(user.id());
    }

    @PostMapping
    public ResponseEntity<TeamDtos.TeamSummary> create(@Valid @RequestBody TeamDtos.CreateTeamRequest request,
                                                       @CurrentUser AuthUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(teamService.create(request.name(), user.id()));
    }

    @Operation(summary = "팀 상세", description = "멤버 목록과 내 권한, 팀 루트 폴더 ID")
    @GetMapping("/{teamId}")
    public TeamDtos.TeamDetail detail(@PathVariable Long teamId, @CurrentUser AuthUser user) {
        return teamService.detail(teamId, user.id());
    }

    @PostMapping("/{teamId}/invitations")
    public ResponseEntity<Void> invite(@PathVariable Long teamId, @Valid @RequestBody TeamDtos.InviteRequest request,
                                       @CurrentUser AuthUser user) {
        teamService.invite(teamId, request.username(), user.id());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/{teamId}/members/{memberId}/permissions")
    public ResponseEntity<Void> updatePermissions(@PathVariable Long teamId, @PathVariable Long memberId,
                                                  @RequestBody TeamDtos.PermissionRequest request,
                                                  @CurrentUser AuthUser user) {
        teamService.updatePermissions(teamId, memberId, request, user.id());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{teamId}/members/{memberId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long teamId, @PathVariable Long memberId,
                                             @CurrentUser AuthUser user) {
        teamService.removeMember(teamId, memberId, user.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{teamId}/leave")
    public ResponseEntity<Void> leave(@PathVariable Long teamId, @CurrentUser AuthUser user) {
        teamService.leave(teamId, user.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{teamId}/leader/{memberId}")
    public ResponseEntity<Void> delegate(@PathVariable Long teamId, @PathVariable Long memberId,
                                         @CurrentUser AuthUser user) {
        teamService.delegateLeadership(teamId, memberId, user.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "팀 삭제", description = "팀 스토리지의 모든 폴더·파일과 채팅이 영구 삭제됩니다.")
    @DeleteMapping("/{teamId}")
    public ResponseEntity<Void> delete(@PathVariable Long teamId, @CurrentUser AuthUser user) {
        teamService.delete(teamId, user.id());
        return ResponseEntity.noContent().build();
    }
}
