package com.smartcollab.file;

import java.util.List;
import java.util.Map;

/**
 * 버전 기록에 함께 보여 줄 버전별 서명. 서명 모듈이 구현합니다 [A-01] — 서명 엔티티가 파일·버전을 가리키므로,
 * 파일 모듈은 서명 모듈을 모르고 이 인터페이스로만 받습니다.
 */
public interface VersionSignatures {

    /** 버전 ID → 그 버전의 서명들(서명 시각 순) */
    Map<Long, List<FileContentService.SignatureResponse>> byVersion(Long fileId);
}
