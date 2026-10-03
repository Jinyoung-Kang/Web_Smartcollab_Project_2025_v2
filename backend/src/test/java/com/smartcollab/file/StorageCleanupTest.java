package com.smartcollab.file;

import com.smartcollab.access.AccessPolicy;
import com.smartcollab.folder.Folder;
import com.smartcollab.folder.FolderRepository;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.tx.TransactionRunner;
import com.smartcollab.storage.BlobLifecycle;
import com.smartcollab.storage.BlobNotFoundException;
import com.smartcollab.storage.BlobStorage;
import com.smartcollab.storage.StoredBlob;
import com.smartcollab.user.User;
import com.smartcollab.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [PERF-01] 저장소 작업을 트랜잭션 밖으로 옮긴 뒤에도, 이어지는 DB 저장이나 다음 저장소 작업이 실패하면
 * 이미 써 둔 파일을 지워 고아 파일을 남기지 않는지 확인합니다.
 */
class StorageCleanupTest {

    private final FileRepository files = mock(FileRepository.class);
    private final FileVersionRepository versions = mock(FileVersionRepository.class);
    private final FolderRepository folders = mock(FolderRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AccessPolicy accessPolicy = mock(AccessPolicy.class);
    private final BlobLifecycle blobLifecycle = mock(BlobLifecycle.class);
    private final BlobStorage storage = mock(BlobStorage.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TransactionRunner tx = mock(TransactionRunner.class);
    private final StorageQuota quota = mock(StorageQuota.class);   // 한도 확인은 통과시키고 정리 동작만 봅니다

    @SuppressWarnings("unchecked")
    private void runReadOnlyWork() {
        doAnswer(inv -> {
            inv.<Runnable>getArgument(0).run();
            return null;
        }).when(tx).readOnly(any(Runnable.class));
        when(tx.readOnly(any(Supplier.class))).thenAnswer(inv -> inv.<Supplier<?>>getArgument(0).get());
    }

    @Test
    @DisplayName("업로드: 저장소에 쓴 뒤 DB 저장이 실패하면(예: 그 사이 폴더 삭제) 방금 쓴 파일을 지운다")
    @SuppressWarnings("unchecked")
    void uploadDiscardsBlobWhenDbWriteFails() {
        runReadOnlyWork();
        Folder folder = mock(Folder.class);
        when(folder.getOwner()).thenReturn(mock(User.class));
        when(folders.findById(10L)).thenReturn(Optional.of(folder));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        when(storage.put(key.capture(), any(InputStream.class), anyLong())).thenAnswer(inv -> new StoredBlob(inv.getArgument(0), 5, "h"));
        when(tx.write(any(Supplier.class))).thenThrow(ApiException.notFound("폴더"));
        FileService service = new FileService(files, versions, folders, users, accessPolicy, blobLifecycle, storage, events, tx, quota);

        assertThatThrownBy(() -> service.upload(10L, UploadSource.of("a.txt", "hello".getBytes()), 1L))
                .isInstanceOf(ApiException.class);

        verify(blobLifecycle).discard(List.of(key.getValue()));
    }

    @Test
    @DisplayName("복사: 두 번째 파일의 저장소 복사가 실패하면 먼저 복사한 파일을 지우고 DB 에는 쓰지 않는다")
    @SuppressWarnings("unchecked")
    void copyDiscardsCopiedBlobsWhenLaterCopyFails() {
        runReadOnlyWork();
        Folder target = mock(Folder.class);
        when(target.getId()).thenReturn(100L);
        when(target.getOwner()).thenReturn(mock(User.class));
        when(folders.findById(100L)).thenReturn(Optional.of(target));
        FileEntity first = file("first.txt", "files/source-1");
        FileEntity second = file("second.txt", "files/source-2");
        when(files.findWithFolder(1L)).thenReturn(Optional.of(first));
        when(files.findWithFolder(2L)).thenReturn(Optional.of(second));
        ArgumentCaptor<String> firstCopy = ArgumentCaptor.forClass(String.class);
        doAnswer(inv -> null).when(storage).copy(eq("files/source-1"), firstCopy.capture());
        doThrow(new BlobNotFoundException("files/source-2")).when(storage).copy(eq("files/source-2"), anyString());
        ItemTransferService service = new ItemTransferService(files, versions, folders, users, accessPolicy, blobLifecycle,
                storage, events, tx, quota, mock(com.smartcollab.folder.FolderDepthPolicy.class),
                mock(com.smartcollab.folder.FolderStructureLock.class), null);   // 파일만 복사 — 폴더 한도는 쓰지 않음

        DriveDtos.TransferRequest request = new DriveDtos.TransferRequest(
                List.of(new DriveDtos.ItemRef("file", 1L), new DriveDtos.ItemRef("file", 2L)), 100L);
        assertThatThrownBy(() -> service.copy(request, 1L)).isInstanceOf(BlobNotFoundException.class);

        verify(blobLifecycle).discard(List.of(firstCopy.getValue()));
        verify(tx, never()).write(any(Supplier.class));
    }

    private static FileEntity file(String name, String storedPath) {
        FileVersion version = mock(FileVersion.class);
        when(version.getStoredPath()).thenReturn(storedPath);
        when(version.getSize()).thenReturn(3L);
        when(version.getSha256()).thenReturn("h");
        FileEntity file = mock(FileEntity.class);
        when(file.getName()).thenReturn(name);
        when(file.getActiveVersion()).thenReturn(version);
        return file;
    }
}
