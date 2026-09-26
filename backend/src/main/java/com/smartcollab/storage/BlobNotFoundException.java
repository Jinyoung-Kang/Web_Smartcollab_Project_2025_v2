package com.smartcollab.storage;

import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.error.ErrorCode;

public class BlobNotFoundException extends ApiException {

    public BlobNotFoundException(String key) {
        super(ErrorCode.NOT_FOUND, "저장소에서 파일 내용을 찾을 수 없습니다.");
    }
}
