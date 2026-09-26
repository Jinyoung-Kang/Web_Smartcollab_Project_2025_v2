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
    implementation("com.azure:azure-storage-blob:12.35.1")
    runtimeOnly("com.mysql:mysql-connector-j")

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
    jvmArgs("-Dfile.encoding=UTF-8", "-XX:+EnableDynamicAgentLoading")
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
