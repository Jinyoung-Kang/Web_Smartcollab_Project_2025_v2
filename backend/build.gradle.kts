plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.1"
}

group = "com.smartcollab"
version = "2.0.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    val bom = platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    implementation(bom)
    compileOnly(bom)
    annotationProcessor(bom)
    testCompileOnly(bom)
    testAnnotationProcessor(bom)

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.flywaydb:flyway-mysql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
    // Azure SDK 는 BOM 으로 버전을 맞추고, 기본 HTTP 클라이언트(Netty 4.1)를 JDK HttpClient 로 교체합니다.
    // Spring Boot 4 가 관리하는 Netty 4.2 와 azure-core-http-netty(4.1) 의 버전 충돌을 원천 차단하기 위함입니다.
    implementation(platform("com.azure:azure-sdk-bom:1.3.8"))
    implementation("com.azure:azure-storage-blob") {
        exclude(group = "com.azure", module = "azure-core-http-netty")
    }
    implementation("com.azure:azure-core-http-jdk-httpclient")
    runtimeOnly("com.mysql:mysql-connector-j")

    // Boot 4.1.1 이 관리하는 Tomcat 11.0.24 에는 CVE-2026-65905·65182·68525 가 있습니다(11.0.25 에서 수정).
    // 이 서비스는 Tomcat 자체 인증·보안 제약을 쓰지 않아 직접 영향은 낮지만, Boot 패치가 나올 때까지 같은 마이너의 패치 버전으로 올립니다.
    // Boot 를 올릴 때 이 블록을 지우고 Boot 가 관리하는 버전이 11.0.25 이상인지 확인하세요.
    constraints {
        listOf("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket").forEach { module ->
            implementation("org.apache.tomcat.embed:$module:11.0.26") {
                because("CVE-2026-65905, CVE-2026-65182, CVE-2026-68525 (fixed in 11.0.25)")
            }
        }
    }

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-websocket-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation"))
}

tasks.test {
    useJUnitPlatform()
    // 운영 컨테이너·CI 와 같은 UTC 로 실행해, 개발 PC 시간대(KST)에서만 통과하는 테스트가 생기지 않게 합니다.
    jvmArgs("-Dfile.encoding=UTF-8", "-Duser.timezone=UTC", "-XX:+EnableDynamicAgentLoading")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}

// 프론트엔드(frontend/) 빌드 결과를 jar 안의 static/ 으로 포함 — `./gradlew bootJar -PbundleFrontend`
val frontendDir = layout.projectDirectory.dir("../frontend")
val bundleFrontend = providers.gradleProperty("bundleFrontend").isPresent

val npmCi by tasks.registering(Exec::class) {
    workingDir(frontendDir)
    commandLine("npm", "ci", "--no-audit", "--no-fund")
    inputs.file(frontendDir.file("package-lock.json"))
    outputs.dir(frontendDir.dir("node_modules"))
}

val npmBuild by tasks.registering(Exec::class) {
    dependsOn(npmCi)
    workingDir(frontendDir)
    commandLine("npm", "run", "build")
    inputs.dir(frontendDir.dir("src"))
    inputs.file(frontendDir.file("index.html"))
    outputs.dir(frontendDir.dir("dist"))
}

tasks.processResources {
    if (bundleFrontend) {
        dependsOn(npmBuild)
        from(frontendDir.dir("dist")) { into("static") }
    }
}

tasks.bootJar { archiveFileName = "smartcollab.jar" }
tasks.jar { enabled = false }
