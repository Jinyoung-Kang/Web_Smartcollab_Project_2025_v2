package com.smartcollab.team;

import com.smartcollab.global.security.AuthUser;
import com.smartcollab.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Invitations", description = "팀 초대 수락·거절")
@RestController
@RequestMapping("/api/invitations")
@RequiredArgsConstructor
public class InvitationController {

    private final TeamService teamService;

    @PostMapping("/{invitationId}/accept")
    public ResponseEntity<Void> accept(@PathVariable Long invitationId, @CurrentUser AuthUser user) {
        teamService.respondToInvitation(invitationId, true, user.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{invitationId}/reject")
    public ResponseEntity<Void> reject(@PathVariable Long invitationId, @CurrentUser AuthUser user) {
        teamService.respondToInvitation(invitationId, false, user.id());
        return ResponseEntity.noContent().build();
    }
}
