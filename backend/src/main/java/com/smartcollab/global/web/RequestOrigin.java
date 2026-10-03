package com.smartcollab.global.web;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 요청을 보낸 브라우저 탭의 ID(X-Client-Id). 이 요청이 일으킨 실시간 이벤트에 실어, 보낸 탭이 자기 변경으로 목록을 한 번 더
 * 불러오지 않게 합니다 [IMP-03]. 형식이 맞지 않으면 없는 것으로 봅니다(헤더 값을 그대로 다른 사용자에게 보내지 않도록).
 * <p>값은 요청 스레드에 두며, 커밋 뒤 이벤트 발행(@TransactionalEventListener AFTER_COMMIT)도 같은 스레드에서 일어납니다.</p>
 */
public final class RequestOrigin {

    public static final String HEADER = "X-Client-Id";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestOrigin() {
    }

    public static Optional<String> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** 이 범위 안에서 현재 요청의 탭 ID 를 둡니다. 형식이 맞지 않으면 비웁니다. */
    public static Scope bind(String clientId) {
        String previous = CURRENT.get();
        if (clientId != null && VALID.matcher(clientId).matches()) {
            CURRENT.set(clientId);
        } else {
            CURRENT.remove();
        }
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
