package com.smartcollab.global.gate;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 접근 코드 잠금 알림 [GATE-02, ADR-0017] — 코드를 받은 사람(채용 담당자 등)이 들어오거나 시도가 잠기면 운영자 텔레그램으로 알립니다.
 * <ul>
 *   <li>알리는 사건은 셋입니다: 잠금 해제 성공(같은 IP 는 1시간에 한 번, 전체로는 10분에 10건까지), IP 하나가 잠긴 순간, 전체 잠금이 걸린 순간.</li>
 *   <li>보내는 정보는 서비스·코드 이름·가린 IP(IPv4 앞 세 자리, IPv6 /48)·브라우저/OS 요약뿐입니다. User-Agent 원문은 보내지 않습니다.</li>
 *   <li>요청 스레드는 큐에 넣기만 하고, 보내기는 작업자 스레드 하나가 합니다(큐 50건, 넘치면 버림). 재시도하지 않고, 실패해도
 *       잠금 해제·차단 응답에는 영향이 없습니다.</li>
 *   <li>봇 토큰은 요청 주소에 들어가므로 주소·토큰·대화 ID 를 로그에 남기지 않습니다. 실패는 상태 코드나 예외 클래스 이름만 남깁니다.</li>
 * </ul>
 * 잠금이 꺼져 있거나({@code GATE_CODES} 없음) 봇 토큰·대화 ID 중 하나라도 없으면 아무것도 하지 않습니다.
 */
@Slf4j
public final class GateNotifier implements AutoCloseable {

    static final String SERVICE = GatePage.TITLE;
    static final String UNKNOWN = "알 수 없음";
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 같은 IP 의 잠금 해제는 이 시간 안에 다시 알리지 않습니다 */
    static final Duration UNLOCK_REPEAT = Duration.ofHours(1);
    /**
     * 잠금 해제 알림은 모든 IP 를 합쳐 이 시간에 {@link #UNLOCK_MAX} 건까지 — 코드를 가진 사람이 IP 를 바꿔 가며 풀어도 알림이 쌓이지 않게.
     * 넘친 해제는 보내지 않고 세어 두었다가 다음 해제 알림에 'k건' 으로 덧붙입니다(전체 기록은 서버 로그의 security-event)
     */
    static final Duration UNLOCK_WINDOW = Duration.ofMinutes(10);
    static final int UNLOCK_MAX = 10;
    static final int MAX_REMEMBERED_IPS = 1000;
    static final int QUEUE_SIZE = 50;
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final URI TELEGRAM_API = URI.create("https://api.telegram.org");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KST);
    /** 요청 주소에 그대로 넣어도 되는 글자만 — 다른 글자가 있으면 주소를 만들다 실패하고, 그 예외 메시지에 토큰이 남습니다 */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9:_-]{1,200}");
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    /**
     * IPv6 리터럴만 InetAddress 로 넘깁니다 — 16진수나 ':' 로 시작하고 ':' 가 있으며 16진수·':'·'.' 만이면, JDK 는 이름 조회(DNS) 없이
     * 리터럴로 해석하거나 거절합니다. 이 검사 없이 넘기면 호스트 이름으로 보고 DNS 를 조회할 수 있습니다.
     */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f.]*:[0-9A-Fa-f:.]*");
    private static final int MAX_IPV6_CHARS = 45;
    private static final int MAX_UA_CHARS = 1024;
    /** 브라우저 — 앞에서부터 처음 맞는 것. 앱 안 브라우저·Edge·Whale·Samsung·Opera 의 UA 에도 Chrome·Safari 가 들어 있습니다 */
    private static final Map<Pattern, String> BROWSERS = ordered(
            "KAKAOTALK|KakaoTalk", "카카오톡",
            "NAVER\\(inapp", "네이버 앱",
            "Whale/", "Whale",
            "SamsungBrowser/", "Samsung Internet",
            "Edg(?:e|A|iOS)?/", "Edge",
            "OPR/|Opera", "Opera",
            "Firefox/|FxiOS/", "Firefox",
            "Chrome/|CriOS/", "Chrome",
            "Version/.*Safari/", "Safari");

    /** OS — iPhone·iPad UA 에도 "Mac OS X", Android UA 에도 "Linux" 가 들어 있습니다 */
    private static final Map<Pattern, String> SYSTEMS = ordered(
            "iPhone", "iOS",
            "iPad", "iPadOS",
            "Android", "Android",
            "Windows", "Windows",
            "Mac OS X|Macintosh", "macOS",
            "CrOS", "ChromeOS",
            "Linux", "Linux");

    private final boolean enabled;
    private final Transport transport;
    private final Clock clock;
    private final ThreadPoolExecutor worker;
    /** IP → 마지막으로 잠금 해제를 알린 시각(ms). 넣은 순서 = 시각 순서. 이 맵의 잠금이 아래 둘도 지킵니다 */
    private final LinkedHashMap<String, Long> lastUnlock = new LinkedHashMap<>();
    /** {@link #UNLOCK_WINDOW} 안에 큐에 넣은 잠금 해제 알림 시각(ms), 오래된 것부터 */
    private final ArrayDeque<Long> recentUnlocks = new ArrayDeque<>();
    /** 아직 알리지 못한 잠금 해제 수(상한·큐가 차서) — 다음 해제 알림에 덧붙입니다 */
    private int unsaid;

    /** 운영 — 텔레그램 Bot API 로 보냅니다 */
    public static GateNotifier create(GateProperties props, ObjectMapper mapper, Clock clock) {
        if (!active(props)) {
            return new GateNotifier(false, null, clock);
        }
        GateProperties.Telegram telegram = props.telegram();
        log.info("Gate alerts are on (Telegram)");
        return new GateNotifier(true, new TelegramTransport(TELEGRAM_API, telegram.botToken(), telegram.chatId(), mapper, TIMEOUT), clock);
    }

    /** 시험 — 전송부를 가짜로 바꿔 끼웁니다. 켜고 끄는 조건은 운영과 같습니다. */
    static GateNotifier create(GateProperties props, Clock clock, Transport transport) {
        return new GateNotifier(active(props), transport, clock);
    }

    /** 잠금이 켜져 있고 봇 토큰·대화 ID 가 모두 있을 때만. 하나만 있거나 토큰 형식이 아니면 경고합니다 — 어떤 이름인지만, 값은 남기지 않음 */
    private static boolean active(GateProperties props) {
        GateProperties.Telegram telegram = props.telegram();
        boolean hasToken = !telegram.botToken().isEmpty();
        boolean hasChat = !telegram.chatId().isEmpty();
        if (hasToken != hasChat) {
            log.warn("Gate alerts are off: {} is empty (set both TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID)",
                    hasToken ? "TELEGRAM_CHAT_ID" : "TELEGRAM_BOT_TOKEN");
            return false;
        }
        if (hasToken && !TOKEN.matcher(telegram.botToken()).matches()) {
            log.warn("Gate alerts are off: TELEGRAM_BOT_TOKEN has characters a bot token does not use");
            return false;
        }
        return props.enabled() && hasToken;
    }

    private GateNotifier(boolean enabled, Transport transport, Clock clock) {
        this.enabled = enabled;
        this.transport = transport;
        this.clock = clock;
        this.worker = enabled ? worker() : null;
    }

    boolean enabled() {
        return enabled;
    }

    /**
     * 맞는 코드로 잠금을 풂 — 같은 IP 는 {@link #UNLOCK_REPEAT} 안에 다시 알리지 않습니다(키는 가리지 않은 전체 IP).
     * 전체로는 {@link #UNLOCK_WINDOW} 에 {@link #UNLOCK_MAX} 건까지 — 넘친 것과 큐가 차 버린 것은 세어 두었다가 다음 해제 알림에 덧붙입니다.
     */
    void unlocked(String codeName, String ip, String userAgent) {
        if (!enabled) {
            return;
        }
        String key = ip == null ? "" : ip;
        synchronized (lastUnlock) {
            long now = clock.millis();
            if (alertedRecently(key, now)) {
                return;
            }
            while (!recentUnlocks.isEmpty() && now - recentUnlocks.peekFirst() >= UNLOCK_WINDOW.toMillis()) {
                recentUnlocks.removeFirst();
            }
            if (recentUnlocks.size() >= UNLOCK_MAX) {   // 기억하지 않음 — 상한이 풀린 뒤 같은 IP 가 다시 풀면 알림
                unsaid++;
                log.warn("Gate alert capped: kind=unlock");
                return;
            }
            String text = "🔓 " + SERVICE + " — 잠금 해제\n"
                    + "코드: " + codeName + "\n"
                    + "시각: " + timestamp() + "\n"
                    + "IP: " + maskIp(ip) + "\n"
                    + "기기: " + device(userAgent);
            if (unsaid > 0) {
                text += "\n그 사이 알리지 못한 잠금 해제 " + unsaid + "건 (" + span(UNLOCK_WINDOW) + "에 " + UNLOCK_MAX + "건까지만 알립니다)";
            }
            if (submit(text)) {
                remember(key, now);
                recentUnlocks.addLast(now);
                unsaid = 0;
            } else {   // 버렸으면 기억하지 않음 — 다음 해제 때 다시 알리고, 이 건도 그때 수에 넣음
                unsaid++;
            }
        }
    }

    /** IP 하나가 잠긴 순간 — 그 시도에서 한 번만 부릅니다(잠긴 동안의 거절마다 부르지 않음) */
    void lockedIp(String ip, String userAgent) {
        if (!enabled) {
            return;
        }
        submit("⛔ " + SERVICE + " — 접근 코드 " + GateLimiter.IP_MAX_FAILS + "회 실패\n"
                + span(GateLimiter.IP_LOCK) + " 동안 이 IP 의 시도를 막습니다\n"
                + "시각: " + timestamp() + "\n"
                + "IP: " + maskIp(ip) + "\n"
                + "기기: " + device(userAgent));
    }

    /** 전체 잠금이 걸린 순간 — 그 시도에서 한 번만 부릅니다 */
    void lockedGlobal() {
        if (!enabled) {
            return;
        }
        submit("🚨 " + SERVICE + " — 전체 잠금\n"
                + span(GateLimiter.GLOBAL_WINDOW) + " 동안 " + GateLimiter.GLOBAL_MAX_FAILS + "회 실패해 "
                + span(GateLimiter.GLOBAL_LOCK) + " 동안 모든 시도를 막습니다\n"
                + "시각: " + timestamp());
    }

    /** 앱이 멈출 때 — 큐에 남은 알림을 최대 {@link #TIMEOUT} 기다려 보내고 작업자를 닫습니다 */
    @Override
    public void close() {
        if (worker == null) {
            return;
        }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (transport instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Gate alert transport did not close: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** 봇 토큰·대화 ID 를 적지 않습니다 */
    @Override
    public String toString() {
        return "GateNotifier[enabled=" + enabled + "]";
    }

    /** 큐에 넣었으면 true. 큐가 찼거나(작업자가 밀림 — 텔레그램이 느리거나 막힘) 닫는 중이면 기다리지 않고 버립니다 */
    private boolean submit(String text) {
        try {
            worker.execute(() -> send(text));
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("Gate alert dropped: {}", worker.isShutdown() ? "shutting down" : "queue full");
        } catch (RuntimeException e) {   // 어떤 경우에도 잠금 응답으로 번지지 않게
            log.warn("Gate alert not queued: {}", e.getClass().getSimpleName());
        }
        return false;
    }

    private void send(String text) {
        try {
            int status = transport.send(text);
            if (status / 100 != 2) {
                log.warn("Gate alert failed: status={}", status);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Gate alert interrupted");
        } catch (Exception e) {   // 예외 메시지에 요청 주소(토큰)가 들어갈 수 있어 클래스 이름만
            log.warn("Gate alert failed: {}", e.getClass().getSimpleName());
        }
    }

    /** 이 IP 의 잠금 해제를 {@link #UNLOCK_REPEAT} 안에 알렸으면 true — lastUnlock 잠금을 쥔 채로 부릅니다 */
    private boolean alertedRecently(String key, long now) {
        Long last = lastUnlock.get(key);
        return last != null && now - last < UNLOCK_REPEAT.toMillis();
    }

    /**
     * 잠금 해제를 알린 IP 를 기록합니다 — lastUnlock 잠금을 쥔 채로, 큐에 넣은 뒤에만 부릅니다. 서비스가 하나라 (서비스, IP) 조합의 키는 IP 입니다.
     * 기억하는 IP 는 {@link #MAX_REMEMBERED_IPS} 개까지 — 넘치면 만료된 것부터, 그래도 넘치면 오래된 것부터 잊습니다.
     */
    private void remember(String key, long now) {
        lastUnlock.remove(key);   // 다시 넣어 순서를 시각 순으로 유지
        forget(now);
        lastUnlock.put(key, now);
    }

    /** 만료된 항목부터 지우고, 그래도 넘치면 오래된 것부터 지웁니다(넣은 순서 = 시각 순서) */
    private void forget(long now) {
        Iterator<Long> oldest = lastUnlock.values().iterator();
        while (oldest.hasNext()) {
            long at = oldest.next();
            if (now - at < UNLOCK_REPEAT.toMillis() && lastUnlock.size() < MAX_REMEMBERED_IPS) {
                break;
            }
            oldest.remove();
        }
    }

    /** 시험 — 해제 알림 상한 때문에 {@link #unlocked} 로는 기억을 {@link #MAX_REMEMBERED_IPS} 개까지 채울 수 없어 직접 기록합니다 */
    void remember(String ip) {
        synchronized (lastUnlock) {
            remember(ip, clock.millis());
        }
    }

    /** 시험 — 이 IP 의 잠금 해제를 {@link #UNLOCK_REPEAT} 안에 알린 것으로 기억하는지 */
    boolean remembers(String ip) {
        synchronized (lastUnlock) {
            return alertedRecently(ip, clock.millis());
        }
    }

    int rememberedIps() {
        synchronized (lastUnlock) {
            return lastUnlock.size();
        }
    }

    private String timestamp() {
        return TIME.format(clock.instant()) + " KST";
    }

    private static String span(Duration d) {
        return d.toMinutes() % 60 == 0 ? d.toHours() + "시간" : d.toMinutes() + "분";
    }

    /** 거절은 기본 처리(예외) — {@link #submit} 이 버린 것을 알아야 잠금 해제를 기억하지 않을 수 있습니다 */
    private static ThreadPoolExecutor worker() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE_SIZE),
                Thread.ofPlatform().name("gate-notify").daemon().factory());
        executor.allowCoreThreadTimeOut(true);   // 알림이 없으면 스레드를 남겨 두지 않음
        return executor;
    }

    /**
     * 메시지에 적을 IP — IPv4 는 앞 세 자리 + ".x", IPv6 은 /48 네트워크, IPv4-mapped·6to4 IPv6 은 그 안의 IPv4 로. 해석하지 못하면 "알 수 없음".
     */
    static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return UNKNOWN;
        }
        String value = ip.strip();
        Matcher v4 = IPV4.matcher(value);
        if (v4.matches()) {
            int[] octets = new int[4];
            for (int i = 0; i < 4; i++) {
                octets[i] = Integer.parseInt(v4.group(i + 1));
                if (octets[i] > 255) {
                    return UNKNOWN;
                }
            }
            return octets[0] + "." + octets[1] + "." + octets[2] + ".x";
        }
        int zone = value.indexOf('%');   // fe80::1%eth0 같은 영역 표시는 떼어 냄
        String literal = zone < 0 ? value : value.substring(0, zone);
        if (literal.length() > MAX_IPV6_CHARS || !IPV6.matcher(literal).matches()) {
            return UNKNOWN;
        }
        try {
            InetAddress address = InetAddress.getByName(literal);
            byte[] b = address.getAddress();
            if (address instanceof Inet4Address) {   // ::ffff:1.2.3.4 는 JDK 가 IPv4 로 돌려줌
                return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".x";
            }
            if (!(address instanceof Inet6Address)) {
                return UNKNOWN;
            }
            // 6to4(2002:AABB:CCDD::) 는 /48 안에 IPv4 32비트가 통째로 들어 있어 그 IPv4 로 가림. Teredo 는 /48 로 드러나지 않아 그대로
            if (b[0] == 0x20 && b[1] == 0x02) {
                return (b[2] & 0xff) + "." + (b[3] & 0xff) + "." + (b[4] & 0xff) + ".x";
            }
            List<String> groups = new ArrayList<>();
            for (int i = 0; i < 6; i += 2) {
                groups.add(Integer.toHexString(((b[i] & 0xff) << 8) | (b[i + 1] & 0xff)));
            }
            while (!groups.isEmpty() && groups.getLast().equals("0")) {   // 뒤쪽 0 은 '::' 로 줄임
                groups.removeLast();
            }
            return String.join(":", groups) + "::/48";
        } catch (UnknownHostException | RuntimeException e) {
            return UNKNOWN;
        }
    }

    /**
     * User-Agent 를 "브라우저 · OS" 로 줄입니다(원문은 보내지 않음). 앱 안 브라우저·Edge·Whale·Samsung·Opera 의 UA 에도 Chrome·Safari 가
     * 들어 있어 순서대로 봅니다. iPad 는 Mac 보다, Android 는 Linux 보다 먼저.
     */
    static String device(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN;
        }
        String ua = userAgent.length() > MAX_UA_CHARS ? userAgent.substring(0, MAX_UA_CHARS) : userAgent;
        String browser = first(ua, BROWSERS);
        String os = first(ua, SYSTEMS);
        if (browser == null && os == null) {
            return UNKNOWN;
        }
        return (browser == null ? UNKNOWN : browser) + " · " + (os == null ? UNKNOWN : os);
    }

    private static String first(String ua, Map<Pattern, String> table) {
        for (Map.Entry<Pattern, String> e : table.entrySet()) {
            if (e.getKey().matcher(ua).find()) {
                return e.getValue();
            }
        }
        return null;
    }

    private static Map<Pattern, String> ordered(String... pairs) {
        Map<Pattern, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(Pattern.compile(pairs[i]), pairs[i + 1]);   // 대소문자 구분 — 구분하지 않으면 "Microsoft" 안의 "cros" 같은 것이 맞음
        }
        return map;
    }

    /** 알림을 실제로 보내는 부분 — 시험은 가짜로 바꿔 끼워 텔레그램을 부르지 않습니다 */
    interface Transport {

        /** @return HTTP 상태 코드 */
        int send(String text) throws Exception;
    }

    /**
     * 텔레그램 Bot API {@code sendMessage} — JDK HttpClient, 연결부터 응답 본문까지 전체 5초, 재시도 없음(429 포함).
     * 평문(parse_mode 없음)이라 코드 이름 등에 든 기호가 서식으로 해석되지 않습니다.
     */
    static final class TelegramTransport implements Transport, AutoCloseable {

        private final URI uri;
        private final String chatId;
        private final ObjectMapper mapper;
        private final Duration timeout;
        private final ExecutorService httpExecutor;
        private final HttpClient client;

        TelegramTransport(URI api, String botToken, String chatId, ObjectMapper mapper, Duration timeout) {
            this.uri = api.resolve("/bot" + botToken + "/sendMessage");
            this.chatId = chatId;
            this.mapper = mapper;
            this.timeout = timeout;
            // HttpClient 기본 실행기는 필요한 만큼 스레드를 늘립니다 — 알림은 한 번에 하나라 스레드 하나로 제한합니다(B1 CPU 1개).
            // 작업자 스레드와 나누면 안 됩니다: 작업자가 send() 로 기다리는 동안 응답 처리가 같은 스레드를 기다려 멈춥니다.
            ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                    Thread.ofPlatform().name("gate-notify-http").daemon().factory());
            executor.allowCoreThreadTimeOut(true);
            this.httpExecutor = executor;
            this.client = HttpClient.newBuilder().connectTimeout(timeout).executor(executor).build();
        }

        @Override
        public int send(String text) throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("link_preview_options", Map.of("is_disabled", true));   // 링크 미리보기 끔 — Bot API 7.0 부터 disable_web_page_preview 를 대신함
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            // 요청의 timeout 은 응답 헤더까지만 셉니다(JDK 는 헤더를 받으면 타이머를 끔). 헤더 뒤 본문이 멈추면 send() 는 연결이 끊길
            // 때까지 기다려 하나뿐인 작업자가 묶이므로, 본문까지 포함한 교환 전체를 같은 상한에서 끊습니다.
            CompletableFuture<HttpResponse<Void>> response = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            try {
                return response.get(timeout.toMillis(), TimeUnit.MILLISECONDS).statusCode();
            } catch (TimeoutException e) {
                throw new HttpTimeoutException("response not completed in time");
            } catch (ExecutionException e) {   // 헤더 시간 초과(HttpTimeoutException)·연결 실패 등은 원래 예외로
                throw e.getCause() instanceof Exception cause ? cause : e;
            } finally {
                response.cancel(true);   // 끝나지 않은 교환이면 연결을 닫음(JDK 16+). 끝난 교환에는 아무 일도 하지 않음
            }
        }

        @Override
        public void close() {
            client.shutdownNow();
            httpExecutor.shutdownNow();
        }

        /** 요청 주소(토큰)·대화 ID 를 적지 않습니다 */
        @Override
        public String toString() {
            return "TelegramTransport";
        }
    }
}
