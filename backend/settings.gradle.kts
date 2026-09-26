plugins {
    // JDK 21 이 없는 환경에서 툴체인을 내려받을 저장소(Foojay). 없으면 Gradle 10 에서 자동 설치가 실패합니다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "smartcollab"
