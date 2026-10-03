package com.smartcollab.signature;

import com.smartcollab.event.DeletionEvents;
import com.smartcollab.event.FileEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 파일 영구 삭제·계정 삭제 때 서명을 지우고 [A-02], 파일 내용이 바뀌면 기존 서명을 무효로 합니다 [A-01].
 * 발행한 쪽의 트랜잭션 안에서 동기로 실행됩니다.
 */
@Component
@RequiredArgsConstructor
class SignatureDeletionListener {

    private final SignatureRepository signatures;

    @EventListener
    void deleteSignaturesOfFiles(DeletionEvents.FilesPurging event) {
        signatures.deleteByFileIds(event.fileIds());
    }

    /** 내용이 바뀌었으므로 기존 서명은 무효 */
    @EventListener
    void invalidateSignatures(FileEvents.CurrentVersionChanged event) {
        signatures.invalidateAll(event.fileId());
    }

    @EventListener
    @Order(DeletionEvents.Order.ACCOUNT_SIGNATURES)
    void deleteSignaturesOfSigner(DeletionEvents.AccountDeleting event) {
        signatures.deleteBySigner(event.userId());
    }
}
