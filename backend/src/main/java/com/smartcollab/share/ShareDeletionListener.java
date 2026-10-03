package com.smartcollab.share;

import com.smartcollab.event.DeletionEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 파일 영구 삭제·계정 삭제 때 공유 링크를 지웁니다 [A-02]. 지우는 쪽의 트랜잭션 안에서 동기로 실행됩니다. */
@Component
@RequiredArgsConstructor
class ShareDeletionListener {

    private final ShareLinkRepository shareLinks;

    @EventListener
    void deleteLinksOfFiles(DeletionEvents.FilesPurging event) {
        shareLinks.deleteByFileIds(event.fileIds());
    }

    /** 내가 만든 공유 링크(팀 파일에 만든 것 포함) */
    @EventListener
    @Order(DeletionEvents.Order.ACCOUNT_SHARE_LINKS)
    void deleteLinksOfOwner(DeletionEvents.AccountDeleting event) {
        shareLinks.deleteByOwner(event.userId());
    }
}
