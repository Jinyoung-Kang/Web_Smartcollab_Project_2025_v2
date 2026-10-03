package com.smartcollab.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기능 모듈(com.smartcollab.* 의 첫 패키지) 사이 의존 규칙 [A-08]. 남은 순환은 기준선으로 고정하고, 새 순환·새 양방향
 * 의존·다른 모듈 리포지토리의 새 사용을 막습니다. 기준선에서 빠지는 항목이 생기면(개선) 기준선도 줄이도록 같음을 확인합니다.
 * <p>남긴 이유는 ADR-0010 에 있습니다: 엔티티 연관(폴더 → 팀·사용자, 파일 → 폴더)과 AccessPolicy 가 엔티티를 받는 데서
 * 생기는 순환은, 엔티티를 ID 참조로 바꾸거나 모듈을 합쳐야 끊을 수 있어 이득보다 비용이 큽니다.</p>
 */
class ModuleBoundaryTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.smartcollab");

    /** 남아 있는 양방향 의존(기준선). 3차 점검 전 17쌍 → 5쌍 */
    private static final Set<String> BIDIRECTIONAL_BASELINE = Set.of(
            "access <-> file", "access <-> folder", "access <-> team",   // AccessPolicy 가 엔티티를 받아 판단
            "file <-> folder",                                            // 한 드라이브의 두 패키지(파일 → 폴더 엔티티, 폴더 내용에 파일)
            "folder <-> team");                                           // 폴더 → 팀 엔티티, 팀 만들 때 최상위 폴더

    /** 순환에 묶여도 되는 모듈(기준선). 3차 점검 전 10개 → 4개 */
    private static final Set<String> CYCLE_BASELINE = Set.of("access", "file", "folder", "team");

    /**
     * 다른 모듈의 리포지토리를 쓰는 곳(기준선). 사용자 조회·저장 한도/구조 변경의 행 잠금·드라이브(파일·폴더)·체험 데이터.
     * 다른 모듈의 데이터를 지우거나 넘기는 일은 리포지토리가 아니라 삭제 이벤트로 합니다(DeletionEvents) [A-02].
     */
    private static final Map<String, Set<String>> FOREIGN_REPOSITORY_BASELINE = Map.of(
            "access", Set.of("TeamMemberRepository"),
            "auth", Set.of("UserRepository"),
            "chat", Set.of("UserRepository"),
            "file", Set.of("FolderRepository", "TeamRepository", "UserRepository"),
            "folder", Set.of("FileRepository", "TeamRepository", "UserRepository"),
            "notification", Set.of("UserRepository"),
            "share", Set.of("UserRepository"),
            "signature", Set.of("UserRepository"),
            "system", Set.of("NotificationRepository", "TeamMemberRepository", "TeamRepository", "UserRepository"),
            "team", Set.of("UserRepository"));

    private static String module(JavaClass c) {
        String pkg = c.getPackageName();
        if (!pkg.startsWith("com.smartcollab.")) return null;
        return pkg.substring("com.smartcollab.".length()).split("\\.")[0];
    }

    /** 모듈 → (의존하는 모듈 → 예시 의존 몇 개) */
    private static Map<String, Map<String, List<String>>> moduleDependencies() {
        Map<String, Map<String, List<String>>> edges = new TreeMap<>();
        for (JavaClass source : CLASSES) {
            String from = module(source);
            if (from == null) continue;
            for (Dependency d : source.getDirectDependenciesFromSelf()) {
                String to = module(d.getTargetClass());
                if (to == null || to.equals(from)) continue;
                List<String> examples = edges.computeIfAbsent(from, k -> new TreeMap<>()).computeIfAbsent(to, k -> new ArrayList<>());
                if (examples.size() < 3) examples.add(source.getSimpleName() + " -> " + d.getTargetClass().getSimpleName());
            }
        }
        return edges;
    }

    @Test
    @DisplayName("[A-08] 모듈 사이 양방향 의존은 기준선(ADR-0010)에 있는 것뿐이다")
    void bidirectionalDependenciesMatchBaseline() {
        Map<String, Map<String, List<String>>> edges = moduleDependencies();
        Map<String, String> pairs = new TreeMap<>();
        edges.forEach((a, targets) -> targets.forEach((b, examples) -> {
            if (a.compareTo(b) < 0 && edges.getOrDefault(b, Map.of()).containsKey(a)) {
                pairs.put(a + " <-> " + b, a + "→" + b + " " + examples + " / " + b + "→" + a + " " + edges.get(b).get(a));
            }
        }));
        assertThat(pairs.keySet())
                .as("새 양방향 의존이면 의존 방향을 바로잡고, 기준선에서 빠졌다면 BIDIRECTIONAL_BASELINE 도 줄이세요: %s", pairs)
                .containsExactlyInAnyOrderElementsOf(BIDIRECTIONAL_BASELINE);
    }

    @Test
    @DisplayName("[A-08] 순환에 묶인 모듈은 기준선(access·file·folder·team) 안에만 있다")
    void cyclesStayWithinBaseline() {
        Map<String, Map<String, List<String>>> edges = moduleDependencies();
        Set<String> inCycles = new TreeSet<>();
        for (Set<String> component : stronglyConnected(edges)) {
            if (component.size() > 1) inCycles.addAll(component);
        }
        assertThat(inCycles)
                .as("새 순환이 생겼거나(의존 방향을 바로잡으세요) 순환에서 빠진 모듈이 있습니다(CYCLE_BASELINE 을 줄이세요)")
                .containsExactlyInAnyOrderElementsOf(CYCLE_BASELINE);
    }

    @Test
    @DisplayName("[A-08] 다른 모듈의 리포지토리는 기준선에 있는 것만 쓴다 (지우기·넘기기는 삭제 이벤트로)")
    void foreignRepositoriesMatchBaseline() {
        Map<String, Set<String>> actual = new TreeMap<>();
        for (JavaClass source : CLASSES) {
            String from = module(source);
            if (from == null) continue;
            for (Dependency d : source.getDirectDependenciesFromSelf()) {
                JavaClass target = d.getTargetClass();
                String to = module(target);
                if (to != null && !to.equals(from) && target.isAssignableTo(Repository.class) && target.isInterface()) {
                    actual.computeIfAbsent(from, k -> new TreeSet<>()).add(target.getSimpleName());
                }
            }
        }
        assertThat(actual).as("다른 모듈의 리포지토리 사용").isEqualTo(new TreeMap<>(FOREIGN_REPOSITORY_BASELINE));
    }

    /** Tarjan 알고리즘으로 강하게 연결된 모듈 묶음(서로 오갈 수 있는 모듈 = 순환) */
    private static List<Set<String>> stronglyConnected(Map<String, Map<String, List<String>>> edges) {
        Set<String> nodes = new TreeSet<>(edges.keySet());
        edges.values().forEach(t -> nodes.addAll(t.keySet()));
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        List<Set<String>> result = new ArrayList<>();
        int[] counter = {0};
        for (String node : nodes) {
            if (!index.containsKey(node)) visit(node, edges, index, low, stack, onStack, result, counter);
        }
        return result;
    }

    private static void visit(String v, Map<String, Map<String, List<String>>> edges, Map<String, Integer> index,
                              Map<String, Integer> low, Deque<String> stack, Set<String> onStack,
                              List<Set<String>> result, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]++);
        stack.push(v);
        onStack.add(v);
        for (String w : edges.getOrDefault(v, Map.of()).keySet()) {
            if (!index.containsKey(w)) {
                visit(w, edges, index, low, stack, onStack, result, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            Set<String> component = new TreeSet<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                component.add(w);
            } while (!w.equals(v));
            result.add(component);
        }
    }
}
