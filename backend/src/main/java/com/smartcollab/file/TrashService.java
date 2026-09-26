package com.smartcollab.file;

import com.smartcollab.access.AccessPolicy;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.realtime.RealtimeEvents;
import com.smartcollab.team.TeamMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 휴지통. v1 은 삭제한 파일을 휴지통으로 옮기기만 하고 목록·복원 화면이 없어, 지운 파일이 저장소에 영원히 남았습니다.
 * v2 는 개인/팀 휴지통 화면과 복원·영구 삭제를 제공하고, 보관 기간(기본 30일)이 지나면 매일 자동으로 비웁니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrashService {

    private final FileRepository files;
    private final AccessPolicy accessPolicy;
    private final DriveCleanupService cleanup;
    private final AppProperties props;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public List<DriveDtos.TrashItem> list(Long teamId, Long userId) {
        List<FileEntity> trashed;
        if (teamId == null) {
            trashed = files.findPersonalTrash(userId);
        } else {
            requireTeamTrashAccess(teamId, userId);
            trashed = files.findTeamTrash(teamId);
        }
        return trashed.stream().map(DriveDtos.TrashItem::of).toList();
    }

    @Transactional
    public void restore(Long fileId, Long userId) {
        FileEntity file = getTrashed(fileId);
        accessPolicy.requireFileDelete(file, userId);
        file.restoreFromTrash();
        Long teamId = file.getFolder().teamId();
        if (teamId != null) {
            events.publishEvent(new RealtimeEvents.FolderChanged(teamId, file.getFolder().getId()));
        }
    }

    @Transactional
    public void deletePermanently(Long fileId, Long userId) {
        FileEntity file = getTrashed(fileId);
        accessPolicy.requireFileDelete(file, userId);
        cleanup.purgeFiles(List.of(file.getId()));
    }

    @Transactional
    public int empty(Long teamId, Long userId) {
        List<Long> ids;
        if (teamId == null) {
            ids = files.findPersonalTrash(userId).stream().map(FileEntity::getId).toList();
        } else {
            requireTeamTrashAccess(teamId, userId);
            ids = files.findTeamTrash(teamId).stream().map(FileEntity::getId).toList();
        }
        return cleanup.purgeFiles(ids);
    }

    /**
     * 보관 기간이 지난 파일을 매일 영구 삭제합니다 (기본: Asia/Seoul 새벽 4시).
     * 시간대를 지정하지 않으면 서버 시간대를 따라, UTC 컨테이너에서는 한국 시각 오후 1시에 실행됐습니다 [BUG-05].
     */
    @Scheduled(cron = "${app.files.trash-purge-cron}", zone = "${app.files.trash-purge-zone}")
    @Transactional
    public void purgeExpired() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(props.files().trashRetentionDays()));
        List<Long> ids = files.findTrashedBefore(cutoff);
        if (!ids.isEmpty()) {
            cleanup.purgeFiles(ids);
            log.info("Purged {} trashed files older than {}", ids.size(), cutoff);
        }
    }

    private void requireTeamTrashAccess(Long teamId, Long userId) {
        TeamMember member = accessPolicy.requireMember(teamId, userId);
        if (!member.mayDelete()) {
            throw ApiException.forbidden("팀 휴지통은 삭제 권한이 있는 멤버만 볼 수 있습니다.");
        }
    }

    private FileEntity getTrashed(Long fileId) {
        FileEntity file = files.findWithFolder(fileId).orElseThrow(() -> ApiException.notFound("파일"));
        if (!file.isDeleted()) {
            throw ApiException.notFound("휴지통의 파일");
        }
        return file;
    }
}
