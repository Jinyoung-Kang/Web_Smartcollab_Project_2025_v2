package com.smartcollab.file;

import org.springframework.stereotype.Component;

@Component
public class OrphanBlobCleaner {
    public record Result(int scanned, int deleted) {
    }

    public Result clean() {
        return new Result(0, 0);   // 임시: 수정 전 상태 확인용
    }
}
