package com.smartcollab.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 계층 구조와 의존 방향 규칙 [ARC-05]. 지금 지켜지고 있는 구조가 기능 추가 중에 무너지지 않도록 테스트로 고정합니다.
 * <ul>
 *   <li>표현(컨트롤러) → 비즈니스(서비스) → 데이터 접근(리포지토리) 순서로만 의존합니다.</li>
 *   <li>공통 모듈(global)과 저장소 어댑터(storage)는 도메인 패키지를 모릅니다 (재사용·교체 가능).</li>
 *   <li>모듈 사이 이벤트(event)는 ID·값만 담고 어느 모듈에도 의존하지 않습니다 [A-03].</li>
 *   <li>API 는 엔티티 대신 DTO 를 돌려줍니다 (비밀번호 해시 같은 내부 필드 노출·지연 로딩 오류 방지).</li>
 * </ul>
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.smartcollab");

    private static final String[] DOMAIN_PACKAGES = {
            "com.smartcollab.access..", "com.smartcollab.ai..", "com.smartcollab.auth..", "com.smartcollab.chat..",
            "com.smartcollab.file..", "com.smartcollab.folder..", "com.smartcollab.notification..",
            "com.smartcollab.realtime..", "com.smartcollab.share..", "com.smartcollab.signature..",
            "com.smartcollab.system..", "com.smartcollab.team..", "com.smartcollab.user.."};

    @Test
    @DisplayName("컨트롤러는 리포지토리를 직접 쓰지 않는다 (서비스를 거친다)")
    void controllersDoNotUseRepositories() {
        noClasses().that().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().areAssignableTo(Repository.class)
                .check(CLASSES);
    }

    @Test
    @DisplayName("서비스는 서블릿(HTTP) 타입에 의존하지 않는다 (요청 해석은 컨트롤러·필터 몫)")
    void servicesDoNotDependOnServletApi() {
        noClasses().that().areAnnotatedWith(Service.class)
                .should().dependOnClassesThat().resideInAPackage("jakarta.servlet..")
                .check(CLASSES);
    }

    @Test
    @DisplayName("컨트롤러는 엔티티를 응답으로 돌려주지 않는다")
    void controllersReturnDtosNotEntities() {
        methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class).and().arePublic()
                .should().notHaveRawReturnType(com.tngtech.archunit.base.DescribedPredicate.describe("an @Entity",
                        (com.tngtech.archunit.core.domain.JavaClass c) -> c.isAnnotatedWith(Entity.class)))
                .check(CLASSES);
    }

    @Test
    @DisplayName("공통 모듈(global)은 도메인 패키지에 의존하지 않는다")
    void globalDoesNotDependOnDomain() {
        noClasses().that().resideInAPackage("com.smartcollab.global..")
                .should().dependOnClassesThat().resideInAnyPackage(DOMAIN_PACKAGES)
                .check(CLASSES);
    }

    @Test
    @DisplayName("파일 저장소 어댑터(storage)는 도메인 패키지에 의존하지 않는다 (Azure·로컬 교체 가능)")
    void storageIsIndependentOfDomain() {
        noClasses().that().resideInAPackage("com.smartcollab.storage..")
                .should().dependOnClassesThat().resideInAnyPackage(DOMAIN_PACKAGES)
                .check(CLASSES);
    }

    @Test
    @DisplayName("[A-03] 모듈 사이 이벤트(event)는 도메인 패키지에 의존하지 않는다 (ID·값만 담음)")
    void eventsAreIndependentOfDomain() {
        noClasses().that().resideInAPackage("com.smartcollab.event..")
                .should().dependOnClassesThat().resideInAnyPackage(DOMAIN_PACKAGES)
                .check(CLASSES);
    }

    @Test
    @DisplayName("[A-05] 서비스는 웹 타입(MultipartFile·HTTP 응답 등)과 컨트롤러에 의존하지 않는다 (요청·응답 형식은 컨트롤러 몫)")
    void servicesDoNotDependOnWebTypes() {
        noClasses().that().areAnnotatedWith(Service.class)
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "org.springframework.http..")
                .orShould().dependOnClassesThat().areAnnotatedWith(RestController.class)
                .check(CLASSES);
    }
}
