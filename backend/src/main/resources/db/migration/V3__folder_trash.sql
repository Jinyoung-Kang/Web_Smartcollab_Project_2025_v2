-- 폴더 휴지통 [UX-06]: 폴더도 파일처럼 지우면 30일 동안 휴지통에 보관합니다.
-- trash_root_id 는 휴지통에 들어간 폴더 트리의 맨 위 폴더를 가리킵니다(맨 위 폴더는 자기 자신).
-- 하위 폴더까지 같은 값을 가져, "이 폴더가 휴지통 안에 있는가"를 상위 폴더를 거슬러 올라가지 않고 한 열로 판단합니다.
-- deleted_at·deleted_by 는 맨 위 폴더에만 기록합니다(보관 기간 계산·"지운 사람" 표시).
ALTER TABLE folders
    ADD COLUMN trash_root_id BIGINT      NULL,
    ADD COLUMN deleted_at    DATETIME(6) NULL,
    ADD COLUMN deleted_by    BIGINT      NULL,
    ADD KEY idx_folders_trash_root (trash_root_id),
    ADD KEY idx_folders_deleted_at (deleted_at),
    ADD CONSTRAINT fk_folders_trash_root FOREIGN KEY (trash_root_id) REFERENCES folders (folder_id),
    ADD CONSTRAINT fk_folders_deleted_by FOREIGN KEY (deleted_by) REFERENCES users (user_id) ON DELETE SET NULL;
