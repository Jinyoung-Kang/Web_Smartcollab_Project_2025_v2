package com.smartcollab.storage;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * [QA6-01] {@link AzureShutdownTest} 가 별도 JVM 으로 실행하는 탐침. Spring 의 정상 종료처럼 JVM 종료 훅 안에서 Azure 저장소를 씁니다
 * (정상 종료는 진행 중인 요청이 끝나기를 종료 훅 안에서 기다리고, 그 요청이 저장소에 블록을 계속 올림).
 */
public final class AzureShutdownProbe {

    private AzureShutdownProbe() {
    }

    public static void main(String[] args) throws Exception {
        AzureBlobStorage storage = StorageConfig.azure(args[0], "shutdown-probe");
        put(storage, "files/before");   // 종료 전에 한 번 써서 SDK 의 실행기를 만들어 둠(운영에서는 이미 쓰는 중)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Thread.sleep(1000);   // 다른 종료 훅(Azure SDK 의 것 포함)이 먼저 돌 시간
                put(storage, "files/during-shutdown");
                System.out.println("PROBE OK");
            } catch (Throwable e) {
                System.out.println("PROBE FAIL " + e);
            }
        }));
        System.exit(0);
    }

    private static void put(AzureBlobStorage storage, String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length);
    }
}
