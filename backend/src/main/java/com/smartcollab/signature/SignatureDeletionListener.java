package com.smartcollab.signature;

import com.smartcollab.event.DeletionEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 파일 영구 삭제·계정 삭제 때 서명을 지웁니다 [A-02]. 지우는 쪽의 트랜잭션 안에서 동기로 실행됩니다. */
@Component
@RequiredArgsConstructor
class SignatureDeletionListener {

    private final SignatureRepository signatures;

    @EventListener
    void deleteSignaturesOfFiles(DeletionEvents.FilesPurging event) {
        signatures.deleteByFileIds(event.fileIds());
    }

    @EventListener
    @Order(DeletionEvents.Order.ACCOUNT_SIGNATURES)
    void deleteSignaturesOfSigner(DeletionEvents.AccountDeleting event) {
        signatures.deleteBySigner(event.userId());
    }
}
