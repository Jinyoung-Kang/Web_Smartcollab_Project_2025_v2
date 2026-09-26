package com.smartcollab.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * 파일 바이트를 보관하는 저장소 추상화 (전략 패턴).
 * 로컬 디스크({@link LocalBlobStorage})와 Azure Blob Storage({@link AzureBlobStorage}) 구현을 설정으로 교체합니다.
 * 키는 서버가 생성한 값(예: {@code files/2f9c...})만 사용하며 사용자 입력을 그대로 쓰지 않습니다.
 */
public interface BlobStorage {

    /** 스트림을 저장하면서 SHA-256 을 함께 계산합니다. */
    StoredBlob put(String key, InputStream content, long size);

    /** 저장된 바이트를 스트림으로 엽니다. 호출자가 닫아야 합니다. */
    InputStream open(String key);

    void copy(String sourceKey, String targetKey);

    /** 없는 키는 조용히 무시합니다 (멱등). */
    void delete(String key);

    /** 외부 서비스(Office 온라인 뷰어)가 읽을 수 있는 임시 URL. 지원하지 않는 저장소는 빈 값. */
    Optional<String> readOnlyUrl(String key, Duration ttl);

    String type();
}
