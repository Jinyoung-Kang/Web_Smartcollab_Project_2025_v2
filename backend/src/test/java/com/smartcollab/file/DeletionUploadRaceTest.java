package com.smartcollab.file;

import com.smartcollab.support.Api;
import com.smartcollab.support.IntegrationTest;
import com.smartcollab.support.TransactionRace;
import com.smartcollab.team.TeamRepository;
import com.smartcollab.team.TeamService;
import com.smartcollab.user.AccountService;
import com.smartcollab.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [L-6] 팀 삭제·탈퇴는 스토리지의 파일 목록을 읽은 뒤 폴더를 지웁니다. 그 사이 같은 스토리지에 업로드가 커밋되면, 목록에 없던 파일이
 * 폴더를 참조해 폴더 삭제가 FK 오류로 실패했습니다(409, 데이터 손실 없음).
 * <ul>
 *   <li>팀 삭제: 업로드는 저장 한도 확인에서 팀 행을 가장 먼저 잠그므로, 팀 삭제도 팀 행을 먼저 잠가 업로드가 끝난 뒤의 목록으로 지웁니다.</li>
 *   <li>탈퇴: 처음에는 사용자 행을 잠그지 않고 "드문 409 — 다시 하면 지워짐"을 알려진 한계로 두었습니다. 사용자 행을 먼저 잠그면 같은 사용자의
 *   <b>팀</b> 업로드와 교착(MySQL 1213)할 수 있었기 때문입니다(팀 업로드는 팀 행·폴더를 쥔 채 작성자 외래 키 확인에서, 탈퇴는 사용자 행을 쥔 채
 *   그 폴더의 작성자를 바꾸려 기다림). 6차 QA 에서 잠그지 않는 방식이 내 드라이브 업로드와도 교착(503)하고 같은 계정의 동시 삭제를 둘 다 성공시키는 것을
 *   확인해, 사용자 행을 먼저 잠그고 교착으로 롤백되면 처음부터 다시 하도록 바꿨습니다 [QA6-06].</li>
 * </ul>
 */
class DeletionUploadRaceTest extends IntegrationTest {

    @Autowired
    FileService fileService;
    @Autowired
    FileRepository files;
    @Autowired
    TeamService teamService;
    @Autowired
    TeamRepository teams;
    @Autowired
    AccountService accountService;
    @Autowired
    UserRepository users;
    @Autowired
    PlatformTransactionManager txManager;

    /** 업로드와 같은 격리 수준 */
    private TransactionRace race() {
        TransactionTemplate readCommitted = new TransactionTemplate(txManager);
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new TransactionRace(readCommitted);
    }

    @Test
    @DisplayName("[L-6] 같은 팀에 업로드가 진행 중일 때 팀을 지우면, 업로드가 끝난 뒤의 파일까지 함께 지우고 성공한다")
    void deleteTeamWhileUploading() throws Exception {
        Api.Session leader = api().signUp("l6t");
        long[] team = leader.createTeam("삭제 경합 팀");

        Throwable result;
        try (TransactionRace race = race()) {
            result = race.run(
                    () -> fileService.upload(team[1], UploadSource.of("late.txt", "늦게 온 파일".getBytes()), leader.userId),
                    () -> teamService.delete(team[0], leader.userId));
        }

        assertThat(result).as("팀 삭제 결과").isNull();
        assertThat(teams.findById(team[0])).isEmpty();
        assertThat(files.findIdsInFolders(List.of(team[1]))).isEmpty();
    }

    @Test
    @DisplayName("[L-6][QA6-06] 내 드라이브 업로드와 겹친 탈퇴는 업로드가 끝난 뒤 그 파일까지 지우고 성공한다 (예전: 드문 409 — 알려진 한계)")
    void deleteAccountWhileUploadingWaitsForUpload() throws Exception {
        Api.Session leaving = api().signUp("l6a");

        Throwable result;
        try (TransactionRace race = race()) {
            result = race.run(
                    () -> fileService.upload(leaving.rootFolderId, UploadSource.of("late.txt", "늦게 온 파일".getBytes()), leaving.userId),
                    () -> accountService.purgeAccount(leaving.userId));
        }

        assertThat(result).as("탈퇴 결과").isNull();
        assertThat(users.findById(leaving.userId)).isEmpty();
        assertThat(files.findIdsInFolders(List.of(leaving.rootFolderId))).isEmpty();
    }
}
