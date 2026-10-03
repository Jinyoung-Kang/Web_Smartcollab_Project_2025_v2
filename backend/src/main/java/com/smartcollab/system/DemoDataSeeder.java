package com.smartcollab.system;

import com.smartcollab.auth.AuthDtos;
import com.smartcollab.auth.AuthService;
import com.smartcollab.chat.ChatDtos;
import com.smartcollab.chat.ChatService;
import com.smartcollab.file.DriveDtos;
import com.smartcollab.file.FileContentService;
import com.smartcollab.file.FileService;
import com.smartcollab.folder.FolderDtos;
import com.smartcollab.folder.FolderService;
import com.smartcollab.folder.RootFolders;
import com.smartcollab.global.config.AppProperties;
import com.smartcollab.notification.NotificationRepository;
import com.smartcollab.share.ShareDtos;
import com.smartcollab.share.ShareService;
import com.smartcollab.signature.SignatureService;
import com.smartcollab.team.TeamDtos;
import com.smartcollab.team.TeamMember;
import com.smartcollab.team.TeamMemberRepository;
import com.smartcollab.team.TeamRepository;
import com.smartcollab.team.TeamService;
import com.smartcollab.user.AccountService;
import com.smartcollab.user.DemoAccounts;
import com.smartcollab.user.User;
import com.smartcollab.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * 데모 모드(DEMO_ENABLED=true)에서 체험용 계정·팀·문서를 만듭니다. 실제 서비스 코드(가입·업로드·버전 저장·서명·채팅)를
 * 그대로 호출하므로 데모 데이터도 운영과 같은 규칙을 거칩니다. 이미 만들어졌으면 건너뜁니다.
 * 문서 내용은 기능 시연용 예시입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataSeeder {

    private final AppProperties props;
    private final UserRepository users;
    private final AuthService authService;
    private final TeamService teamService;
    private final TeamMemberRepository members;
    private final FolderService folderService;
    private final RootFolders rootFolders;
    private final FileService fileService;
    private final FileContentService contentService;
    private final SignatureService signatureService;
    private final ChatService chatService;
    private final ShareService shareService;
    private final NotificationRepository notifications;
    private final TeamRepository teams;
    private final AccountService accountService;

    /**
     * 체험 데이터 초기화 [SEC-06]. 체험 계정은 여러 방문자가 함께 쓰므로, 누가 무엇을 바꿨든 주기적으로 처음 상태로 되돌립니다.
     * 체험 계정이 팀장인 팀(방문자가 만든 팀 포함)과 체험 계정을 모두 지운 뒤 다시 만듭니다.
     * 일반 사용자의 데이터는 건드리지 않으며, 체험 팀에 들어와 있던 일반 사용자에게는 팀 삭제 알림이 갑니다.
     */
    @Scheduled(cron = "${app.demo.reset-cron}", zone = "${app.demo.reset-zone}")
    public void reset() {
        if (!props.demo().enabled()) return;
        List<User> demoUsers = DemoAccounts.ACCOUNTS.stream()
                .map(a -> users.findByUsername(a.username()))
                .flatMap(Optional::stream)
                .toList();
        try {
            for (User user : demoUsers) {
                for (Long teamId : teams.findIdsOwnedBy(user.getId())) {
                    teamService.purgeTeam(teamId, null);
                }
            }
            for (User user : demoUsers) {
                accountService.purgeAccount(user.getId());
            }
        } catch (RuntimeException e) {
            // 일부만 지워진 상태로 다시 만들면 계정이 겹치므로, 다음 초기화 때 남은 것부터 다시 지웁니다.
            log.error("Demo data reset failed; will retry at the next scheduled reset", e);
            return;
        }
        seed();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!props.demo().enabled()) return;
        String password = props.demo().password();
        if (password == null || password.length() < 8) {
            log.warn("DEMO_ENABLED=true 이지만 DEMO_PASSWORD(8자 이상, 영문+숫자)가 없어 데모 데이터를 만들지 않습니다.");
            return;
        }
        if (users.existsByUsername(DemoAccounts.ACCOUNTS.getFirst().username())) return;

        User leader = signUp(DemoAccounts.ACCOUNTS.get(0), password);
        User editor = signUp(DemoAccounts.ACCOUNTS.get(1), password);
        User deleter = signUp(DemoAccounts.ACCOUNTS.get(2), password);

        // 팀 구성: 초대 → 수락 과정을 그대로 거칩니다.
        TeamDtos.TeamSummary team = teamService.createDemoTeam("SmartCollab 데모 팀", leader.getId());
        joinTeam(team.id(), leader, editor);
        joinTeam(team.id(), leader, deleter);
        TeamMember deleterMember = members.findByTeamIdAndUserId(team.id(), deleter.getId()).orElseThrow();
        teamService.updatePermissions(team.id(), deleterMember.getId(), new TeamDtos.PermissionRequest(true, true, false),
                leader.getId());

        Long root = team.rootFolderId();
        Long planning = folder(root, "기획", leader);
        Long minutes = folder(root, "회의록", leader);
        Long design = folder(root, "디자인", deleter);

        DriveDtos.ItemResponse overview = upload(planning, "프로젝트 개요.md", PROJECT_OVERVIEW, leader);
        upload(planning, "요구사항 체크리스트.csv", REQUIREMENTS_CSV, editor);

        DriveDtos.ItemResponse kickoff = upload(minutes, "킥오프 회의.md", KICKOFF_V1, leader);
        save(kickoff.id(), KICKOFF_V2, editor);
        signatureService.sign(kickoff.id(), leader.getId());                // v2 에 서명
        save(kickoff.id(), KICKOFF_V3, deleter);                           // 새 버전 → v2 서명은 무효
        signatureService.sign(kickoff.id(), leader.getId());                // v3 에 다시 서명
        upload(minutes, "주간 회의 메모.txt", WEEKLY_MEMO, deleter);

        DriveDtos.ItemResponse sketch = upload(design, "화면 스케치.png", sketchPng(), deleter);
        upload(design, "색상 팔레트.json", PALETTE_JSON, deleter);

        chat(team.id(), leader, "킥오프 회의록 올려 두었어요. 고칠 부분이 있으면 바로 수정해 주세요.");
        chat(team.id(), editor, "액션 아이템을 정리해서 새 버전으로 저장했습니다.");
        chatService.post(team.id(), new ChatDtos.SendRequest(null, sketch.id()), deleter.getId());
        chat(team.id(), deleter, "화면 스케치 첫 안입니다. 의견 부탁드려요!");
        chat(team.id(), leader, "확인했어요. 최종 회의록은 제가 서명해 두었습니다.");

        shareService.create(overview.id(), new ShareDtos.CreateRequest(null, 24 * 7, 20), leader.getId());

        // 개인 드라이브와 휴지통
        Long personalRoot = rootFolders.personalOf(leader).getId();
        Long refs = folder(personalRoot, "참고 자료", leader);
        upload(personalRoot, "할 일.txt", TODO, leader);
        upload(refs, "읽을거리.md", READING, leader);
        DriveDtos.ItemResponse old = upload(personalRoot, "지난 초안.txt", "이전 버전의 초안입니다.", leader);
        fileService.moveToTrash(old.id(), leader.getId());

        // 알림: 다른 팀에서 온 초대(수락/거절 대기)
        TeamDtos.TeamSummary review = teamService.createDemoTeam("디자인 리뷰", editor.getId());
        teamService.invite(review.id(), leader.getUsername(), editor.getId());

        // 데모 첫 화면이 읽은 알림으로 어수선하지 않도록 처리 완료된 알림은 읽음으로 표시
        notifications.findRecent(leader.getId(), PageRequest.of(0, 50)).stream()
                .filter(n -> n.getInvitation() == null)
                .forEach(n -> notifications.save(markRead(n)));
        log.info("Demo data created: accounts {}", DemoAccounts.ACCOUNTS.stream().map(DemoAccounts.Account::username).toList());
    }

    private static com.smartcollab.notification.Notification markRead(com.smartcollab.notification.Notification n) {
        n.markRead();
        return n;
    }

    private User signUp(DemoAccounts.Account a, String password) {
        return authService.signUp(new AuthDtos.SignUpRequest(a.username(), password, password, a.name(), null));
    }

    private void joinTeam(Long teamId, User leader, User member) {
        teamService.invite(teamId, member.getUsername(), leader.getId());
        Long invitationId = notifications.findRecent(member.getId(), PageRequest.of(0, 1)).getFirst().getInvitation().getId();
        teamService.respondToInvitation(invitationId, true, member.getId());
    }

    private Long folder(Long parentId, String name, User user) {
        return folderService.create(new FolderDtos.CreateFolderRequest(parentId, name), user.getId()).id();
    }

    private DriveDtos.ItemResponse upload(Long folderId, String name, String text, User user) {
        return upload(folderId, name, text.getBytes(StandardCharsets.UTF_8), user);
    }

    private DriveDtos.ItemResponse upload(Long folderId, String name, byte[] bytes, User user) {
        return fileService.upload(folderId, new BytesMultipartFile(name, bytes), user.getId());
    }

    private Long save(Long fileId, String text, User user) {
        Long base = contentService.readText(fileId, user.getId()).versionId();
        return contentService.saveText(fileId, text, base, user.getId()).versionId();
    }

    private void chat(Long teamId, User user, String text) {
        chatService.post(teamId, new ChatDtos.SendRequest(text, null), user.getId());
    }

    /** 간단한 화면 와이어프레임 이미지 (글자 없이 도형만 — 서버 폰트에 의존하지 않음) */
    private static byte[] sketchPng() {
        BufferedImage img = new BufferedImage(960, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0xF8FAFC));
        g.fillRect(0, 0, 960, 600);
        g.setColor(new Color(0x2563EB));
        g.fillRect(0, 0, 960, 64);
        g.setColor(new Color(0xE2E8F0));
        g.fillRoundRect(24, 88, 200, 488, 16, 16);
        g.setColor(Color.WHITE);
        g.fillRoundRect(248, 88, 688, 488, 16, 16);
        g.setColor(new Color(0xCBD5E1));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(248, 88, 688, 488, 16, 16);
        for (int i = 0; i < 6; i++) {
            g.setColor(new Color(0xBFDBFE));
            g.fillRoundRect(272, 150 + i * 64, 40, 40, 8, 8);
            g.setColor(new Color(0xE2E8F0));
            g.fillRoundRect(328, 158 + i * 64, 360 - i * 30, 12, 6, 6);
            g.fillRoundRect(328, 178 + i * 64, 180, 8, 4, 4);
        }
        for (int i = 0; i < 5; i++) {
            g.setColor(i == 1 ? new Color(0x93C5FD) : new Color(0xCBD5E1));
            g.fillRoundRect(40, 112 + i * 44, 168, 28, 8, 8);
        }
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    private static final String PROJECT_OVERVIEW = """
            # 사내 문서 협업 공간 도입 프로젝트 (예시 문서)

            ## 배경
            팀마다 파일을 메신저와 개인 PC에 나눠 보관하고 있어 최신본을 찾는 데 시간이 오래 걸린다.
            같은 문서를 여러 사람이 고치면서 누가 언제 무엇을 바꿨는지 추적하기 어렵다.

            ## 목표
            모든 팀 문서를 한 곳에서 관리하고 수정 이력과 승인 기록을 남긴다.
            외부 협력사에는 기간과 횟수가 제한된 링크로만 파일을 전달한다.

            ## 범위
            1단계에서는 기획팀과 디자인팀의 문서를 옮긴다.
            2단계에서는 영업팀 자료와 외부 공유 절차를 정리한다.

            ## 일정
            1주차에 폴더 구조와 권한 규칙을 확정한다.
            2주차에 기존 문서를 옮기고 3주차에 팀별 사용 교육을 진행한다.

            ## 성공 기준
            최신본을 찾는 시간이 줄어야 하고, 외부로 나간 파일은 링크 단위로 회수할 수 있어야 한다.
            """;

    private static final String KICKOFF_V1 = """
            # 킥오프 회의 (예시 문서)

            - 참석: 김하늘, 이도윤, 박서연
            - 안건: 폴더 구조, 권한 규칙, 일정

            ## 논의 내용
            폴더는 기획, 회의록, 디자인 세 가지로 시작한다.
            삭제 권한은 최소 인원에게만 준다.
            """;

    private static final String KICKOFF_V2 = KICKOFF_V1 + """

            ## 액션 아이템
            1. 이도윤: 요구사항 체크리스트 작성
            2. 박서연: 화면 스케치 초안 공유
            """;

    private static final String KICKOFF_V3 = KICKOFF_V2 + """
            3. 김하늘: 외부 공유 링크 사용 규칙 정리 (비밀번호·유효 기간 필수)
            """;

    private static final String WEEKLY_MEMO = """
            주간 회의 메모 (예시 문서)
            - 체크리스트 1차 완료
            - 화면 스케치 피드백 반영 예정
            - 다음 회의에서 외부 공유 규칙 확정
            """;

    private static final String REQUIREMENTS_CSV = """
            번호,요구사항,우선순위,상태
            1,팀별 폴더와 권한 분리,높음,완료
            2,문서 수정 이력 보관,높음,완료
            3,승인(서명) 기록,중간,진행 중
            4,외부 공유 링크 만료 설정,높음,진행 중
            5,채팅으로 파일 공유,낮음,대기
            """;

    private static final String PALETTE_JSON = """
            {
              "primary": "#2563EB",
              "surface": "#F8FAFC",
              "border": "#E2E8F0",
              "danger": "#DC2626"
            }
            """;

    private static final String TODO = """
            할 일 (예시)
            - 킥오프 회의록 최종본 서명
            - 디자인 리뷰 팀 초대 확인
            """;

    private static final String READING = """
            # 읽을거리 (예시)
            - 파일 협업 도구 비교 정리
            - 외부 공유 보안 체크리스트
            """;
}
