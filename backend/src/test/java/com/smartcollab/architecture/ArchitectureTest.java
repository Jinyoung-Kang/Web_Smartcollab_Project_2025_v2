package com.smartcollab.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.Repository;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
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
    @DisplayName("컨트롤러(REST·STOMP)는 리포지토리를 직접 쓰지 않는다 (서비스를 거친다)")
    void controllersDoNotUseRepositories() {
        // [L-7] @RestController 만 봐서 STOMP 의 @Controller 는 규칙 밖이었음
        noClasses().that().areMetaAnnotatedWith(Controller.class)
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
    @DisplayName("컨트롤러는 엔티티를 응답으로 돌려주지 않는다 (List<엔티티>·ResponseEntity<엔티티> 같은 타입 인자 포함)")
    void controllersReturnDtosNotEntities() {
        // [L-7] 예전 규칙은 반환 타입 자체만 봐서 List<엔티티>·ResponseEntity<엔티티>·Optional<엔티티> 를 놓쳤음
        methods().that().areDeclaredInClassesThat().areMetaAnnotatedWith(Controller.class).and().arePublic()
                .should(new ArchCondition<JavaMethod>("not return an @Entity, even as a type argument") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        method.getReturnType().getAllInvolvedRawTypes().stream()
                                .filter(type -> type.isAnnotatedWith(Entity.class))
                                .forEach(entity -> events.add(SimpleConditionEvent.violated(method,
                                        method.getFullName() + " returns entity " + entity.getName())));
                    }
                })
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

    @Test
    @DisplayName("[L-7] 컨트롤러·설정 밖의 도메인 클래스는 들어오는 HTTP 요청·응답 타입(서블릿·업로드 파일·요청 바인딩·ResponseEntity)에 의존하지 않는다")
    void domainComponentsDoNotDependOnInboundWebTypes() {
        // @Service 만 보던 규칙을 @Component 등 나머지 도메인 클래스로 넓힘. 바깥으로 나가는 HTTP 클라이언트(DeepL)·웹소켓 이벤트는 허용
        noClasses().that().resideInAnyPackage(DOMAIN_PACKAGES)
                .and().areNotMetaAnnotatedWith(Controller.class)
                .and().areNotMetaAnnotatedWith(Configuration.class)
                .and(DescribedPredicate.not(enclosedInController()))
                .should().dependOnClassesThat().resideInAnyPackage("jakarta.servlet..", "org.springframework.web.multipart..",
                        "org.springframework.web.bind..", "org.springframework.web.servlet..")
                .orShould().dependOnClassesThat().areAssignableTo(ResponseEntity.class)
                .check(CLASSES);
    }

    @Test
    @DisplayName("[L-7] 요청·응답 DTO(…Dtos 안의 레코드, …Request·…Response)의 구성요소에 엔티티를 담지 않는다 (타입 인자 포함)")
    void dtoComponentsAreNotEntities() {
        // 컨트롤러 반환 타입만 보면 DTO 안에 엔티티를 넣어 그대로 직렬화하는 경로(비밀번호 해시 노출·지연 로딩 오류)를 놓칩니다
        fields().that().areDeclaredInClassesThat(dto()).and().areNotStatic()
                .should(notInvolveEntities("hold an @Entity, even as a type argument"))
                .check(CLASSES);
    }

    @Test
    @DisplayName("[L-7] 요청 본문(@RequestBody·STOMP @Payload)으로 엔티티를 받지 않는다 (요청 값이 엔티티 필드에 그대로 묶이는 것 방지)")
    void requestBodiesAreNotEntities() {
        methods().that().areDeclaredInClassesThat().areMetaAnnotatedWith(Controller.class)
                .should(new ArchCondition<JavaMethod>("not bind a request body to an @Entity") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        method.getParameters().stream()
                                .filter(p -> p.isAnnotatedWith(RequestBody.class) || p.isAnnotatedWith(Payload.class))
                                .flatMap(p -> p.getType().getAllInvolvedRawTypes().stream())
                                .filter(type -> type.isAnnotatedWith(Entity.class))
                                .forEach(entity -> events.add(SimpleConditionEvent.violated(method,
                                        method.getFullName() + " binds request body to entity " + entity.getName())));
                    }
                })
                .check(CLASSES);
    }

    private static DescribedPredicate<JavaClass> dto() {
        return DescribedPredicate.describe("request/response DTOs", c -> c.isRecord() && (
                c.getSimpleName().endsWith("Request") || c.getSimpleName().endsWith("Response")
                        || c.getEnclosingClass().map(outer -> outer.getSimpleName().endsWith("Dtos")).orElse(false)));
    }

    private static ArchCondition<JavaField> notInvolveEntities(String description) {
        return new ArchCondition<>("not " + description) {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                field.getType().getAllInvolvedRawTypes().stream()
                        .filter(type -> type.isAnnotatedWith(Entity.class))
                        .forEach(entity -> events.add(SimpleConditionEvent.violated(field,
                                field.getFullName() + " holds entity " + entity.getName())));
            }
        };
    }

    /** 컨트롤러 안에 둔 요청·응답 레코드 */
    private static DescribedPredicate<JavaClass> enclosedInController() {
        return DescribedPredicate.describe("nested in a controller", c -> c.getEnclosingClass()
                .map(outer -> outer.isMetaAnnotatedWith(Controller.class)).orElse(false));
    }
}
