-- 알림 목록("내 알림 최신순 30개")을 정렬 없이 인덱스 순서로 읽도록 합니다 [PERF-04].
-- 기존 idx_notifications_user (user_id, is_read, created_at) 는 읽지 않은 알림 수를 세는 데 계속 씁니다.
CREATE INDEX idx_notifications_user_recent ON notifications (user_id, created_at, notification_id);
