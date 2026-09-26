-- SmartCollab v2 초기 스키마 (MySQL 8.0+)
-- 모든 시각은 UTC 로 저장합니다 (hibernate.jdbc.time_zone=UTC).

CREATE TABLE users (
    user_id    BIGINT       NOT NULL AUTO_INCREMENT,
    username   VARCHAR(50)  NOT NULL,
    password   VARCHAR(100) NOT NULL,
    name       VARCHAR(50)  NOT NULL,
    email      VARCHAR(100) NULL,
    role       VARCHAR(20)  NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE teams (
    team_id    BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100) NOT NULL,
    owner_id   BIGINT       NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (team_id),
    KEY idx_teams_owner (owner_id),
    CONSTRAINT fk_teams_owner FOREIGN KEY (owner_id) REFERENCES users (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE team_members (
    team_member_id BIGINT      NOT NULL AUTO_INCREMENT,
    team_id        BIGINT      NOT NULL,
    user_id        BIGINT      NOT NULL,
    is_team_leader BOOLEAN     NOT NULL,
    can_edit       BOOLEAN     NOT NULL,
    can_delete     BOOLEAN     NOT NULL,
    can_invite     BOOLEAN     NOT NULL,
    joined_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (team_member_id),
    UNIQUE KEY uk_team_members_team_user (team_id, user_id),
    KEY idx_team_members_user (user_id),
    CONSTRAINT fk_team_members_team FOREIGN KEY (team_id) REFERENCES teams (team_id),
    CONSTRAINT fk_team_members_user FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE invitations (
    invitation_id BIGINT      NOT NULL AUTO_INCREMENT,
    team_id       BIGINT      NOT NULL,
    inviter_id    BIGINT      NOT NULL,
    invitee_id    BIGINT      NOT NULL,
    status        VARCHAR(20) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    responded_at  DATETIME(6) NULL,
    PRIMARY KEY (invitation_id),
    KEY idx_invitations_invitee_status (invitee_id, status),
    KEY idx_invitations_team (team_id),
    CONSTRAINT fk_invitations_team FOREIGN KEY (team_id) REFERENCES teams (team_id),
    CONSTRAINT fk_invitations_inviter FOREIGN KEY (inviter_id) REFERENCES users (user_id),
    CONSTRAINT fk_invitations_invitee FOREIGN KEY (invitee_id) REFERENCES users (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE folders (
    folder_id        BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(255) NOT NULL,
    owner_id         BIGINT       NOT NULL,
    team_id          BIGINT       NULL,
    parent_folder_id BIGINT       NULL,
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (folder_id),
    KEY idx_folders_parent (parent_folder_id),
    KEY idx_folders_team (team_id),
    KEY idx_folders_owner_team (owner_id, team_id),
    CONSTRAINT fk_folders_owner FOREIGN KEY (owner_id) REFERENCES users (user_id),
    CONSTRAINT fk_folders_team FOREIGN KEY (team_id) REFERENCES teams (team_id),
    CONSTRAINT fk_folders_parent FOREIGN KEY (parent_folder_id) REFERENCES folders (folder_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE files (
    file_id           BIGINT       NOT NULL AUTO_INCREMENT,
    folder_id         BIGINT       NOT NULL,
    owner_id          BIGINT       NOT NULL,
    original_name     VARCHAR(255) NOT NULL,
    size              BIGINT       NOT NULL,
    active_version_id BIGINT       NULL,
    is_deleted        BOOLEAN      NOT NULL,
    deleted_at        DATETIME(6)  NULL,
    deleted_by        BIGINT       NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    version           BIGINT       NOT NULL,
    PRIMARY KEY (file_id),
    KEY idx_files_folder_deleted (folder_id, is_deleted),
    KEY idx_files_owner_deleted (owner_id, is_deleted),
    KEY idx_files_deleted_at (is_deleted, deleted_at),
    CONSTRAINT fk_files_folder FOREIGN KEY (folder_id) REFERENCES folders (folder_id),
    CONSTRAINT fk_files_owner FOREIGN KEY (owner_id) REFERENCES users (user_id),
    CONSTRAINT fk_files_deleted_by FOREIGN KEY (deleted_by) REFERENCES users (user_id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE file_versions (
    version_id  BIGINT      NOT NULL AUTO_INCREMENT,
    file_id     BIGINT      NOT NULL,
    stored_path VARCHAR(100) NOT NULL,
    editor_id   BIGINT      NOT NULL,
    size        BIGINT      NOT NULL,
    sha256      CHAR(64)    NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (version_id),
    UNIQUE KEY uk_file_versions_stored_path (stored_path),
    KEY idx_file_versions_file (file_id, version_id),
    CONSTRAINT fk_file_versions_file FOREIGN KEY (file_id) REFERENCES files (file_id),
    CONSTRAINT fk_file_versions_editor FOREIGN KEY (editor_id) REFERENCES users (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- files ↔ file_versions 는 서로를 참조하므로 FK 를 나중에 추가합니다.
ALTER TABLE files
    ADD CONSTRAINT fk_files_active_version FOREIGN KEY (active_version_id) REFERENCES file_versions (version_id);

CREATE TABLE signatures (
    signature_id BIGINT      NOT NULL AUTO_INCREMENT,
    file_id      BIGINT      NOT NULL,
    version_id   BIGINT      NOT NULL,
    signer_id    BIGINT      NOT NULL,
    sha256       CHAR(64)    NOT NULL,
    is_valid     BOOLEAN     NOT NULL,
    signed_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (signature_id),
    UNIQUE KEY uk_signatures_version_signer (version_id, signer_id),
    KEY idx_signatures_file (file_id),
    CONSTRAINT fk_signatures_file FOREIGN KEY (file_id) REFERENCES files (file_id),
    CONSTRAINT fk_signatures_version FOREIGN KEY (version_id) REFERENCES file_versions (version_id),
    CONSTRAINT fk_signatures_signer FOREIGN KEY (signer_id) REFERENCES users (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE share_links (
    link_id        BIGINT       NOT NULL AUTO_INCREMENT,
    file_id        BIGINT       NOT NULL,
    owner_id       BIGINT       NOT NULL,
    token          VARCHAR(64)  NOT NULL,
    password_hash  VARCHAR(100) NULL,
    expires_at     DATETIME(6)  NULL,
    download_limit INT          NULL,
    download_count INT          NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (link_id),
    UNIQUE KEY uk_share_links_token (token),
    KEY idx_share_links_file (file_id),
    KEY idx_share_links_owner (owner_id),
    CONSTRAINT fk_share_links_file FOREIGN KEY (file_id) REFERENCES files (file_id),
    CONSTRAINT fk_share_links_owner FOREIGN KEY (owner_id) REFERENCES users (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE chat_messages (
    message_id BIGINT        NOT NULL AUTO_INCREMENT,
    team_id    BIGINT        NOT NULL,
    sender_id  BIGINT        NOT NULL,
    type       VARCHAR(20)   NOT NULL,
    content    VARCHAR(2000) NOT NULL,
    file_id    BIGINT        NULL,
    file_name  VARCHAR(255)  NULL,
    file_size  BIGINT        NULL,
    created_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (message_id),
    KEY idx_chat_messages_team (team_id, message_id),
    CONSTRAINT fk_chat_messages_team FOREIGN KEY (team_id) REFERENCES teams (team_id),
    CONSTRAINT fk_chat_messages_sender FOREIGN KEY (sender_id) REFERENCES users (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE notifications (
    notification_id BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    type            VARCHAR(40)  NOT NULL,
    content         VARCHAR(500) NOT NULL,
    invitation_id   BIGINT       NULL,
    team_id         BIGINT       NULL,
    is_read         BOOLEAN      NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (notification_id),
    KEY idx_notifications_user (user_id, is_read, created_at),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_notifications_invitation FOREIGN KEY (invitation_id) REFERENCES invitations (invitation_id) ON DELETE SET NULL,
    CONSTRAINT fk_notifications_team FOREIGN KEY (team_id) REFERENCES teams (team_id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
