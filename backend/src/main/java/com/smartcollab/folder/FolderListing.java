package com.smartcollab.folder;

import com.smartcollab.file.DriveDtos;
import com.smartcollab.global.error.ApiException;
import com.smartcollab.global.util.NaturalOrder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 폴더 목록의 정렬과 나누기 [IMP-02]. 폴더가 늘 앞이고, 선택한 열로 정렬하며 같으면 이름(자연 정렬)·ID 순입니다 — 화면이 하던 정렬을
 * 그대로 옮겼습니다. 커서는 다음 묶음의 위치를 담은 불투명한 값이라 클라이언트는 해석하지 않고 그대로 돌려줍니다.
 */
public final class FolderListing {

    public static final int DEFAULT_LIMIT = 500;
    public static final int MAX_LIMIT = 1000;
    private static final String CURSOR_PREFIX = "o";

    private static final Map<String, Comparator<DriveDtos.ItemResponse>> SORTS = Map.of(
            "name", Comparator.comparing(DriveDtos.ItemResponse::name, NaturalOrder.INSTANCE),
            "ownerName", Comparator.comparing(DriveDtos.ItemResponse::ownerName, NaturalOrder.INSTANCE),
            "updatedAt", Comparator.comparing(DriveDtos.ItemResponse::updatedAt),
            "size", Comparator.comparing((DriveDtos.ItemResponse i) -> i.size() == null ? -1L : i.size()));

    private static final Comparator<DriveDtos.ItemResponse> FOLDERS_FIRST =
            Comparator.comparing((DriveDtos.ItemResponse i) -> "folder".equals(i.type()) ? 0 : 1);

    private static final Comparator<DriveDtos.ItemResponse> TIE_BREAK =
            Comparator.comparing(DriveDtos.ItemResponse::name, NaturalOrder.INSTANCE).thenComparing(DriveDtos.ItemResponse::id);

    private FolderListing() {
    }

    /** 검증한 목록 요청 */
    public record Request(int limit, int offset, String sort, boolean ascending) {
        public static final Request DEFAULT = new Request(DEFAULT_LIMIT, 0, "name", true);
    }

    public static Request request(Integer limit, String cursor, String sort, String order) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw ApiException.badRequest("limit 은 1~" + MAX_LIMIT + " 사이여야 합니다.");
        }
        String key = sort == null ? "name" : sort;
        if (!SORTS.containsKey(key)) {
            throw ApiException.badRequest("sort 는 name·updatedAt·ownerName·size 중 하나입니다.");
        }
        String dir = order == null ? "asc" : order;
        if (!dir.equals("asc") && !dir.equals("desc")) {
            throw ApiException.badRequest("order 는 asc 또는 desc 입니다.");
        }
        return new Request(size, decode(cursor), key, dir.equals("asc"));
    }

    /** 폴더 우선 → 선택한 열(방향 적용) → 이름·ID */
    static List<DriveDtos.ItemResponse> sort(List<DriveDtos.ItemResponse> items, Request request) {
        Comparator<DriveDtos.ItemResponse> key = SORTS.get(request.sort());
        Comparator<DriveDtos.ItemResponse> order = FOLDERS_FIRST
                .thenComparing(request.ascending() ? key : key.reversed())
                .thenComparing(TIE_BREAK);
        List<DriveDtos.ItemResponse> sorted = new ArrayList<>(items);
        sorted.sort(order);
        return sorted;
    }

    static String encode(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((CURSOR_PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!raw.startsWith(CURSOR_PREFIX)) throw new IllegalArgumentException();
            int offset = Integer.parseInt(raw.substring(CURSOR_PREFIX.length()));
            if (offset < 0) throw new IllegalArgumentException();
            return offset;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("목록 커서가 올바르지 않습니다. 처음부터 다시 불러오세요.");
        }
    }
}
