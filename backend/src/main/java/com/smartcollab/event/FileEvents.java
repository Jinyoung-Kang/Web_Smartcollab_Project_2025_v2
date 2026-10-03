package com.smartcollab.event;

/**
 * 파일 모듈이 같은 트랜잭션 안에서 다른 모듈에 알리는 이벤트들. 듣는 쪽은 동기 @EventListener 로 처리합니다.
 */
public final class FileEvents {

    private FileEvents() {
    }

    /** 파일의 현재 버전이 바뀜(새 버전 저장·버전 복원). 내용이 바뀌었으므로 그 파일의 기존 서명을 무효로 합니다. */
    public record CurrentVersionChanged(Long fileId) {
    }
}
