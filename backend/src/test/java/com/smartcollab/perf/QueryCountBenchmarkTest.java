package com.smartcollab.perf;

import com.smartcollab.file.FileRepository;
import com.smartcollab.file.FileService;
import com.smartcollab.folder.Folder;
import com.smartcollab.folder.FolderRepository;
import com.smartcollab.folder.FolderService;
import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import com.smartcollab.team.TeamService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조회 쿼리 수 측정. 같은 DB·같은 데이터에서
 * <ul>
 *   <li>v1 방식: 폴더마다 하위 목록/파일을 따로 조회하는 재귀(지연 로딩과 같은 쿼리 패턴)</li>
 *   <li>v2 방식: 범위 전체를 한 번에 읽고 메모리에서 트리 구성 / 재귀 CTE</li>
 * </ul>
 * 을 실행해 Hibernate 통계의 "준비된 SQL 수"를 비교하고, 결과를 build/reports/metrics/query-counts.json 에 남깁니다.
 */
class QueryCountBenchmarkTest extends IntegrationTest {

    static final int FOLDERS = 60;   // 루트 아래 3단계 트리 (4 × 4 × 3 + 4 × 4 + 4 = 68 에 가깝게)

    @Autowired
    EntityManagerFactory emf;
    @Autowired
    FolderService folderService;
    @Autowired
    FolderRepository folders;
    @Autowired
    FileRepository files;
    @Autowired
    FileService fileService;
    @Autowired
    TeamService teamService;
    @Autowired
    TransactionTemplate tx;

    Statistics stats() {
        Statistics s = emf.unwrap(SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        return s;
    }

    long measure(Runnable action) {
        Statistics s = stats();
        s.clear();
        action.run();
        return s.getPrepareStatementCount();
    }

    @Test
    @DisplayName("폴더 트리·검색·팀 목록의 쿼리 수: 폴더/팀 수와 무관하게 상수")
    void queryCounts() throws Exception {
        Api.Session s = api().signUp("perf");
        List<Long> created = new ArrayList<>();
        // 4 × 4 × 3 = 48 + 4 × 4 = 16 → 루트 제외 68개 폴더 중 FOLDERS 개 생성
        outer:
        for (int a = 0; a < 4; a++) {
            long fa = s.createFolder(s.rootFolderId, "A" + a);
            created.add(fa);
            for (int b = 0; b < 4; b++) {
                long fb = s.createFolder(fa, "B" + b);
                created.add(fb);
                s.uploadText(fb, "보고서-" + a + b + ".txt", "x");
                for (int c = 0; c < 3; c++) {
                    if (created.size() >= FOLDERS) break outer;
                    created.add(s.createFolder(fb, "C" + c));
                }
            }
        }
        int folderCount = created.size() + 1;
        for (int t = 0; t < 5; t++) {
            s.createTeam("팀" + t);
        }

        long v2Tree = measure(() -> folderService.tree(null, s.userId));
        long v1Tree = measure(() -> tx.executeWithoutResult(st -> legacyTree(folders.findPersonalRoot(s.userId).orElseThrow())));
        long v2Search = measure(() -> fileService.search("보고서", null, s.userId));
        long v1Search = measure(() -> tx.executeWithoutResult(st -> legacySearch(folders.findPersonalRoot(s.userId).orElseThrow(), "보고서")));
        long v2Teams = measure(() -> teamService.myTeams(s.userId));
        long v2Contents = measure(() -> folderService.contents(created.get(created.size() - 1), s.userId));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("folders", folderCount);
        report.put("teams", 5);
        report.put("folderTree", Map.of("v1Pattern", v1Tree, "v2", v2Tree));
        report.put("fileSearch", Map.of("v1Pattern", v1Search, "v2", v2Search));
        report.put("myTeams", Map.of("v2", v2Teams));
        report.put("folderContentsDepth3", Map.of("v2", v2Contents));
        write("query-counts.json", report);

        assertThat(v2Tree).isLessThanOrEqualTo(2);
        assertThat(v2Search).isLessThanOrEqualTo(3);
        assertThat(v2Teams).isLessThanOrEqualTo(4);
        assertThat(v1Tree).isGreaterThanOrEqualTo(folderCount);
        assertThat(v1Search).isGreaterThanOrEqualTo(folderCount);
    }

    /** v1 FolderTreeDto: 폴더마다 subFolders 컬렉션을 지연 로딩 → 폴더 수만큼 SELECT */
    private int legacyTree(Folder folder) {
        int n = 1;
        for (Folder child : folders.findChildren(folder.getId())) {
            n += legacyTree(child);
        }
        return n;
    }

    /** v1 findFilesRecursive: 폴더마다 파일 검색 1회 + 하위 폴더 로딩 1회 */
    private void legacySearch(Folder folder, String q) {
        files.findActiveInFolder(folder.getId()).stream().filter(f -> f.getName().contains(q)).count();
        for (Folder child : folders.findChildren(folder.getId())) {
            legacySearch(child, q);
        }
    }

    static void write(String name, Map<String, Object> report) throws IOException {
        Path dir = Path.of("build", "reports", "metrics");
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Object> e : report.entrySet()) {
            sb.append("  \"").append(e.getKey()).append("\": ").append(toJson(e.getValue()));
            sb.append(++i < report.size() ? ",\n" : "\n");
        }
        sb.append("}\n");
        Files.writeString(dir.resolve(name), sb.toString());
    }

    @SuppressWarnings("unchecked")
    private static String toJson(Object v) {
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            int i = 0;
            for (Map.Entry<?, ?> e : ((Map<Object, Object>) m).entrySet()) {
                if (i++ > 0) sb.append(", ");
                sb.append('"').append(e.getKey()).append("\": ").append(toJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        return v instanceof String str ? "\"" + str + "\"" : String.valueOf(v);
    }
}
