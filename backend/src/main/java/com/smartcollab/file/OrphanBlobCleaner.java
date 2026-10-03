package com.smartcollab.file;

import com.smartcollab.storage.BlobStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 고아 저장소 파일 정리 [IMP-05]. 업로드·복사는 저장소에 먼저 쓰고 DB 에 나중에 쓰며, 삭제는 DB 커밋 뒤에 저장소에서 지웁니다(ADR-0002).
 * 그래서 그 사이에 서버가 죽거나 삭제가 실패하면 DB 가 가리키지 않는 파일이 남습니다(출시 기준 QA: 폴더 복사 도중 강제 종료로 150개).
 * 매일 저장소의 파일을 훑어, 유예 시간(기본 24시간)보다 오래됐고 어떤 버전도 가리키지 않는 파일만 지웁니다.
 * <ul>
 *   <li>유예 시간 안의 파일은 지금 올라오는 중(아직 DB 에 없음)일 수 있어 남깁니다.</li>
 *   <li>오래된 고아는 다시 쓰일 수 없습니다 — 새 버전은 늘 새 키를 쓰고, 이미 있는 키를 나중에 가리키는 경로가 없습니다.</li>
 *   <li>쓰다 남은 임시 파일(.upload-*.tmp)도 키가 버전에 없으므로 함께 지워집니다.</li>
 * </ul>
 */
@Slf4j
@Component
public class OrphanBlobCleaner {

    /** 버전 파일이 놓이는 접두어(BlobLifecycle.newFileKey·텍스트 저장) */
    private static final List<String> PREFIXES = List.of("files/", "versions/");
    private static final int BATCH = 500;

    private final BlobStorage storage;
    private final FileVersionRepository versions;
    private final Duration grace;

    public OrphanBlobCleaner(BlobStorage storage, FileVersionRepository versions,
                             @Value("${app.files.orphan-grace:24h}") Duration grace) {
        this.storage = storage;
        this.versions = versions;
        this.grace = grace;
    }

    public record Result(int scanned, int deleted) {
    }

    @Scheduled(cron = "${app.files.orphan-cleanup-cron:0 0 5 * * *}", zone = "${app.files.trash-purge-zone:Asia/Seoul}")
    public void scheduled() {
        clean();
    }

    public Result clean() {
        Instant cutoff = Instant.now().minus(grace);
        int[] scanned = {0};
        int[] deleted = {0};
        List<String> batch = new ArrayList<>();
        for (String prefix : PREFIXES) {
            storage.list(prefix, blob -> {
                scanned[0]++;
                if (blob.lastModified().isBefore(cutoff)) {
                    batch.add(blob.key());
                    if (batch.size() == BATCH) deleted[0] += deleteUnreferenced(batch);
                }
            });
        }
        deleted[0] += deleteUnreferenced(batch);
        if (deleted[0] > 0) {
            log.info("Orphan blobs deleted: {} of {} scanned (older than {})", deleted[0], scanned[0], grace);
        }
        return new Result(scanned[0], deleted[0]);
    }

    private int deleteUnreferenced(List<String> keys) {
        if (keys.isEmpty()) return 0;
        Set<String> referenced = new HashSet<>(versions.findReferencedStoredPaths(keys));
        int deleted = 0;
        for (String key : keys) {
            if (!referenced.contains(key)) {
                storage.delete(key);
                deleted++;
            }
        }
        keys.clear();
        return deleted;
    }
}
