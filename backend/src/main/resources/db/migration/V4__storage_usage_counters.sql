-- 저장 사용량 집계 [IMP-01]
-- 저장 한도 확인이 업로드·저장마다 그 범위의 모든 버전을 합산했습니다(O(파일 수), 출시 기준 QA 의 느린 쿼리 1위).
-- 사용자(개인 저장 공간)·팀 행에 옛 버전·휴지통을 포함한 실제 저장량을 두고, 버전을 넣고 지우는 트랜잭션에서 함께 고칩니다.
-- 어긋남은 매일 StorageUsageReconciler 가 실제 합계로 바로잡습니다.
ALTER TABLE users ADD COLUMN stored_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE teams ADD COLUMN stored_bytes BIGINT NOT NULL DEFAULT 0;

-- 기존 데이터의 집계를 채웁니다(실제 합계로 계산 — 값만 채우고 기존 데이터는 바꾸지 않음)
UPDATE users u
SET u.stored_bytes = (SELECT COALESCE(SUM(v.size), 0)
                      FROM file_versions v
                               JOIN files f ON f.file_id = v.file_id
                               JOIN folders fo ON fo.folder_id = f.folder_id
                      WHERE fo.team_id IS NULL
                        AND fo.owner_id = u.user_id);

UPDATE teams t
SET t.stored_bytes = (SELECT COALESCE(SUM(v.size), 0)
                      FROM file_versions v
                               JOIN files f ON f.file_id = v.file_id
                               JOIN folders fo ON fo.folder_id = f.folder_id
                      WHERE fo.team_id = t.team_id);
