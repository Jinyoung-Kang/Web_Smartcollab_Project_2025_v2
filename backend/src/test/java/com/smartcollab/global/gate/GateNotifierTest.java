package com.smartcollab.global.gate;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [GATE-02] 접근 코드 잠금 알림 — 메시지 형식·KST·가린 IP·기기 요약·같은 IP 1시간 억제·해제 알림 전체 상한·켜는 조건·전송 실패 무시·큐 상한·비밀값 가림.
 * 전송부는 가짜로 바꿔 끼워 실제 텔레그램을 부르지 않고, HTTP 전송부는 이 JVM 안의 시험 서버로 확인합니다.
 */
@ExtendWith(OutputCaptureExtension.class)
class GateNotifierTest {

    private static final String CODES = "recruit:scrypt.1024.8.1.AAAA.AAAA";
    private static final String TOKEN = "123456:telegram-token-marker-0008";
    private static final String CHAT = "chat-id-marker-0009";
    private static final String CHROME_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    /** 2026-10-15 10:32 KST */
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T01:32:00Z"));
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final List<GateNotifier> created = new ArrayList<>();

    @AfterEach
    void close() {
        created.forEach(GateNotifier::close);
    }

    @Test
    @DisplayName("[GATE-02] 잠금 해제 알림은 평문 다섯 줄 — 서비스·코드 이름·KST 시각·가린 IP·기기 (IP·User-Agent 원문은 보내지 않음)")
    void unlockMessage() {
        GateNotifier notifier = notifier(recording());

        notifier.unlocked("recruit", "211.234.12.34", CHROME_WINDOWS);

        assertThat(drain(notifier)).containsExactly("""
                🔓 SmartCollab — 잠금 해제
                코드: recruit
                시각: 2026-10-15 10:32 KST
                IP: 211.234.12.x
                기기: Chrome · Windows""");
        assertThat(sent.getFirst()).doesNotContain("211.234.12.34", "Mozilla", "Win64");
    }

    @Test
    @DisplayName("[GATE-02] IP 잠금·전체 잠금 알림 — 시도 제한 기준(5회·15분, 1시간 30회·30분)·KST 시각·가린 IP·기기")
    void lockMessages() {
        GateNotifier notifier = notifier(recording());

        notifier.lockedIp("2001:db8:1:2::5", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 "
                + "(KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1");
        notifier.lockedGlobal();

        assertThat(drain(notifier)).containsExactly("""
                ⛔ SmartCollab — 접근 코드 5회 실패
                15분 동안 이 IP 의 시도를 막습니다
                시각: 2026-10-15 10:32 KST
                IP: 2001:db8:1::/48
                기기: Safari · iOS""", """
                🚨 SmartCollab — 전체 잠금
                1시간 동안 30회 실패해 30분 동안 모든 시도를 막습니다
                시각: 2026-10-15 10:32 KST""");
    }

    @Test
    @DisplayName("[GATE-02] 시각은 서버 시간대와 상관없이 KST(UTC+9) — UTC 15시 이후는 다음 날짜")
    void timeIsKst() {
        clock.set(Instant.parse("2026-10-14T15:30:00Z"));
        GateNotifier notifier = notifier(recording());

        notifier.lockedGlobal();

        assertThat(drain(notifier).getFirst()).endsWith("시각: 2026-10-15 00:30 KST");
    }

    @Test
    @DisplayName("[GATE-02] IP 가리기 — IPv4 앞 세 자리, IPv6 /48, IPv4-mapped·6to4 는 IPv4 로, 해석할 수 없으면 '알 수 없음'")
    void masksIp() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry("211.234.12.34", "211.234.12.x"),
                Map.entry(" 10.0.0.1 ", "10.0.0.x"),
                Map.entry("2001:db8:1:2:3:4:5:6", "2001:db8:1::/48"),
                Map.entry("2001:0db8:0000:0042::1", "2001:db8::/48"),
                Map.entry("2001:0:0:1::1", "2001::/48"),
                Map.entry("::1", "::/48"),
                Map.entry("0:0:0:0:0:0:0:1", "::/48"),
                Map.entry("fe80::1%eth0", "fe80::/48"),
                Map.entry("::ffff:1.2.3.4", "1.2.3.x"),
                Map.entry("::ffff:0102:0304", "1.2.3.x"),
                Map.entry("2002:d3ea:c22::1", "211.234.12.x"),   // 6to4 — /48 에 IPv4(211.234.12.34)가 통째로 들어 있음
                Map.entry("256.1.1.1", "알 수 없음"),
                Map.entry("1.2.3", "알 수 없음"),
                Map.entry("1:2", "알 수 없음"),
                Map.entry("2001:db8::/48", "알 수 없음"),
                Map.entry("unknown", "알 수 없음"),
                Map.entry("example.com", "알 수 없음"),
                Map.entry(".:1", "알 수 없음"),
                Map.entry("", "알 수 없음"));

        cases.forEach((ip, masked) -> assertThat(GateNotifier.maskIp(ip)).as(ip).isEqualTo(masked));
        assertThat(GateNotifier.maskIp(null)).isEqualTo("알 수 없음");
    }

    @Test
    @DisplayName("[GATE-02] 기기 요약 — 앱 안 브라우저·Edge·Whale·Samsung·Opera 를 Chrome·Safari 보다 먼저, iPad 는 Mac 보다 먼저 판별")
    void summarizesUserAgent() {
        Map<String, String> cases = Map.ofEntries(
                Map.entry(CHROME_WINDOWS, "Chrome · Windows"),
                Map.entry(CHROME_WINDOWS + " Edg/141.0.0.0", "Edge · Windows"),
                Map.entry("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 "
                        + "Whale/4.29.282.14 Safari/537.36", "Whale · Windows"),
                Map.entry(CHROME_WINDOWS + " OPR/125.0.0.0", "Opera · Windows"),
                Map.entry("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 "
                        + "Safari/605.1.15", "Safari · macOS"),
                Map.entry("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                        + "Mobile/15E148 KAKAOTALK 10.8.0", "카카오톡 · iOS"),
                Map.entry("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                        + "EdgiOS/141.0 Version/18.0 Mobile/15E148 Safari/604.1", "Edge · iOS"),
                Map.entry("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                        + "FxiOS/144.0 Mobile/15E148 Safari/605.1.15", "Firefox · iOS"),
                Map.entry("Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/141.0.0.0 "
                        + "Mobile/15E148 Safari/604.1", "Chrome · iPadOS"),
                Map.entry("Mozilla/5.0 (Linux; Android 14; SM-S921N) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 "
                        + "Chrome/122.0.0.0 Mobile Safari/537.36", "Samsung Internet · Android"),
                Map.entry("Mozilla/5.0 (Linux; Android 14; SM-S921N Build/UP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Version/4.0 Chrome/122.0.0.0 Mobile Safari/537.36 NAVER(inapp; search; 2000; 12.6.0)", "네이버 앱 · Android"),
                Map.entry("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 "
                        + "Mobile Safari/537.36 EdgA/141.0", "Edge · Android"),
                Map.entry("Mozilla/5.0 (X11; Linux x86_64; rv:144.0) Gecko/20100101 Firefox/144.0", "Firefox · Linux"),
                Map.entry("Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 "
                        + "Safari/537.36", "Chrome · ChromeOS"),
                Map.entry("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", "알 수 없음 · Windows"),
                Map.entry("KnowledgeBot/1.0 Microsoft", "알 수 없음"),
                Map.entry("curl/8.7.1", "알 수 없음"),
                Map.entry(" ", "알 수 없음"));

        cases.forEach((ua, device) -> assertThat(GateNotifier.device(ua)).as(ua).isEqualTo(device));
        assertThat(GateNotifier.device(null)).isEqualTo("알 수 없음");
    }

    @Test
    @DisplayName("[GATE-02] 같은 IP 의 잠금 해제는 1시간 안에 다시 알리지 않는다 — 키는 전체 IP 라 가린 IP 가 같아도 다른 IP 면 알림")
    void suppressesRepeatUnlocksForAnHour() {
        GateNotifier notifier = notifier(recording());

        notifier.unlocked("recruit", "211.234.12.34", CHROME_WINDOWS);
        clock.advance(Duration.ofMinutes(59));
        notifier.unlocked("recruit", "211.234.12.34", CHROME_WINDOWS);   // 1시간 안 — 알리지 않음
        notifier.unlocked("recruit", "211.234.12.35", CHROME_WINDOWS);   // 가린 IP 는 같지만 다른 IP
        clock.advance(Duration.ofMinutes(2));
        notifier.unlocked("other", "211.234.12.34", CHROME_WINDOWS);     // 처음 알린 뒤 61분

        List<String> messages = drain(notifier);
        assertThat(messages).hasSize(3);
        assertThat(messages.get(2)).contains("코드: other", "시각: 2026-10-15 11:33 KST");
    }

    @Test
    @DisplayName("[GATE-02] 중복 판단에 기억하는 IP 는 1000개까지 — 만료된 것부터, 그래도 넘치면 오래된 것부터 잊는다")
    void boundsRememberedIps() {
        GateNotifier notifier = notifier(recording());

        for (int i = 0; i < 10; i++) {
            notifier.remember("192.0.2." + i);
        }
        clock.advance(GateNotifier.UNLOCK_REPEAT);
        notifier.remember("198.51.100.1");
        assertThat(notifier.rememberedIps()).isEqualTo(1);   // 만료된 10개는 지움

        for (int i = 0; i < GateNotifier.MAX_REMEMBERED_IPS + 5; i++) {
            clock.advance(Duration.ofMillis(1));
            notifier.remember("10." + (i / 65536) + "." + (i / 256 % 256) + "." + (i % 256));
        }
        assertThat(notifier.rememberedIps()).isEqualTo(GateNotifier.MAX_REMEMBERED_IPS);
        assertThat(notifier.remembers("198.51.100.1")).as("가장 오래된 IP 는 잊음").isFalse();
        assertThat(notifier.remembers("10.0.3.236")).as("최근 IP 는 기억").isTrue();
    }

    @Test
    @DisplayName("[GATE-02] 잠금 해제 알림은 모든 IP 를 합쳐 10분에 10건까지 — 넘친 것은 보내지도 기억하지도 않고, 그 수를 다음 해제 알림에 한 번만 덧붙인다")
    void capsUnlockAlertsOverall(CapturedOutput output) {
        GateNotifier notifier = notifier(recording());
        int extra = 5;

        for (int i = 0; i < GateNotifier.UNLOCK_MAX + extra; i++) {   // 같은 /64 안에서 주소만 바꿔 가며 해제
            notifier.unlocked("recruit", "2001:db8:1:2::" + Integer.toHexString(i), CHROME_WINDOWS);
        }
        notifier.unlocked("recruit", "2001:db8:1:2::0", CHROME_WINDOWS);   // 1시간 억제를 먼저 봄 — 넘친 수에 넣지 않음
        notifier.lockedIp("198.51.100.7", CHROME_WINDOWS);   // 잠금 알림은 상한과 상관없음(GateLimiter 가 이미 묶음)
        notifier.lockedGlobal();
        assertThat(notifier.rememberedIps()).isEqualTo(GateNotifier.UNLOCK_MAX);   // 넘친 것은 기억하지 않음
        clock.advance(GateNotifier.UNLOCK_WINDOW.minusMillis(1));
        notifier.unlocked("recruit", "211.234.12.34", CHROME_WINDOWS);   // 아직 창 안 — 처음 보는 IP 라도 넘친 것
        clock.advance(Duration.ofMillis(1));   // 첫 10건에서 10분 — 창을 벗어남
        notifier.unlocked("other", "2001:db8:1:2::" + Integer.toHexString(GateNotifier.UNLOCK_MAX), CHROME_WINDOWS);   // 넘쳤던 IP — 이제 알림
        notifier.unlocked("recruit", "211.234.12.35", CHROME_WINDOWS);

        List<String> messages = drain(notifier);
        assertThat(messages).hasSize(GateNotifier.UNLOCK_MAX + 4);
        assertThat(messages.subList(GateNotifier.UNLOCK_MAX, GateNotifier.UNLOCK_MAX + 2))
                .satisfiesExactly(m -> assertThat(m).startsWith("⛔"), m -> assertThat(m).startsWith("🚨"));
        assertThat(messages.get(GateNotifier.UNLOCK_MAX + 2)).isEqualTo("""
                🔓 SmartCollab — 잠금 해제
                코드: other
                시각: 2026-10-15 10:42 KST
                IP: 2001:db8:1::/48
                기기: Chrome · Windows
                그 사이 알리지 못한 잠금 해제 6건 (10분에 10건까지만 알립니다)""");
        assertThat(messages.getLast()).contains("IP: 211.234.12.x").endsWith("기기: Chrome · Windows");   // 덧붙임은 한 번만
        assertThat(output.getOut().split("Gate alert capped: kind=unlock", -1)).hasSize(extra + 2);   // 넘친 6건마다 한 줄
        assertThat(output.getOut()).doesNotContain("2001:db8:1:2::", "211.234.12.34");
    }

    @Test
    @DisplayName("[GATE-02] 큐가 차서 버린 잠금 해제는 기억하지 않는다 — 같은 IP 가 다시 풀면 알리고, 버린 수를 그 알림에 덧붙인다")
    void droppedUnlockIsNotRemembered(CapturedOutput output) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1 + GateNotifier.QUEUE_SIZE);
        GateNotifier notifier = notifier(text -> {
            started.countDown();
            release.await();
            sent.add(text);
            delivered.countDown();
            return 200;
        });

        notifier.unlocked("recruit", "211.234.12.34", CHROME_WINDOWS);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();   // 첫 알림은 작업자가 꺼내 보내는 중
        for (int i = 0; i < GateNotifier.QUEUE_SIZE; i++) {
            notifier.lockedGlobal();   // 큐를 채움(잠금 알림은 해제 상한과 상관없음)
        }
        notifier.unlocked("recruit", "198.51.100.7", CHROME_WINDOWS);   // 큐가 차 버림
        assertThat(notifier.remembers("198.51.100.7")).isFalse();
        assertThat(notifier.rememberedIps()).isEqualTo(1);
        release.countDown();
        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();   // 큐가 비었음
        notifier.unlocked("recruit", "198.51.100.7", CHROME_WINDOWS);   // 기억하지 않았으므로 다시 알림
        notifier.unlocked("recruit", "198.51.100.8", CHROME_WINDOWS);

        List<String> messages = drain(notifier);
        assertThat(messages).hasSize(GateNotifier.QUEUE_SIZE + 3);
        assertThat(messages.get(GateNotifier.QUEUE_SIZE + 1)).contains("IP: 198.51.100.x")
                .endsWith("\n그 사이 알리지 못한 잠금 해제 1건 (10분에 10건까지만 알립니다)");
        assertThat(messages.getLast()).endsWith("기기: Chrome · Windows");   // 덧붙임은 한 번만
        assertThat(output.getOut()).contains("Gate alert dropped: queue full").doesNotContain("Gate alert capped");
    }

    @Test
    @DisplayName("[GATE-02] 잠금이 꺼져 있거나 봇 토큰·대화 ID 중 하나라도 없으면 보내지 않고, 하나만 있으면 어떤 이름이 비었는지만 경고한다")
    void disabledUnlessGateAndBothSettingsArePresent(CapturedOutput output) {
        List<GateNotifier> off = List.of(
                notifier(new GateProperties("", "", "", null, new GateProperties.Telegram(TOKEN, CHAT)), recording()),
                notifier(new GateProperties(CODES, "", "", null, new GateProperties.Telegram(TOKEN, "  ")), recording()),
                notifier(new GateProperties(CODES, "", "", null, new GateProperties.Telegram(null, CHAT)), recording()),
                notifier(new GateProperties(CODES, "", "", null, new GateProperties.Telegram("123456:bad token-marker", CHAT)), recording()),
                notifier(new GateProperties(CODES, "", "", null, null), recording()));

        for (GateNotifier notifier : off) {
            notifier.unlocked("recruit", "211.234.12.34", CHROME_WINDOWS);
            notifier.lockedIp("211.234.12.34", CHROME_WINDOWS);
            notifier.lockedGlobal();
            assertThat(notifier.enabled()).isFalse();
            notifier.close();
        }

        assertThat(sent).isEmpty();
        assertThat(output.getOut()).contains("Gate alerts are off: TELEGRAM_CHAT_ID is empty",
                        "Gate alerts are off: TELEGRAM_BOT_TOKEN is empty", "TELEGRAM_BOT_TOKEN has characters")
                .doesNotContain("telegram-token-marker", "chat-id-marker", "bad token-marker");
        assertThat(new GateProperties.Telegram("  " + TOKEN + "\n", " " + CHAT + " "))
                .isEqualTo(new GateProperties.Telegram(TOKEN, CHAT));   // 앞뒤 공백은 지움
    }

    @Test
    @DisplayName("[GATE-02] application.yml 이 환경변수 TELEGRAM_BOT_TOKEN·TELEGRAM_CHAT_ID 를 app.gate.telegram 으로 읽고 앞뒤 공백을 지운다")
    void bindsTelegramSettings() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("env", Map.<String, Object>of(   // 운영에서 넣는 환경변수 자리
                "GATE_CODES", CODES,
                "TELEGRAM_BOT_TOKEN", " " + TOKEN + " ",
                "TELEGRAM_CHAT_ID", CHAT + "\n")));
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml")).forEach(sources::addLast);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));

        GateProperties props = binder.bind("app.gate", Bindable.of(GateProperties.class)).get();

        assertThat(props.telegram()).isEqualTo(new GateProperties.Telegram(TOKEN, CHAT));
        assertThat(props.telegram().enabled()).isTrue();
        assertThat(notifier(props, recording()).enabled()).isTrue();
    }

    @Test
    @DisplayName("[GATE-02] 전송 실패(예외·429)는 다시 보내지 않고 상태 코드·예외 클래스 이름만 남긴다 — 다음 알림은 그대로 보낸다")
    void ignoresTransportFailures(CapturedOutput output) {
        List<String> attempts = new CopyOnWriteArrayList<>();
        GateNotifier notifier = notifier(text -> {
            attempts.add(text);
            return switch (attempts.size()) {
                case 1 -> throw new IllegalStateException("POST https://api.telegram.org/bot" + TOKEN + "/sendMessage failed");
                case 2 -> 429;
                default -> 200;
            };
        });

        notifier.unlocked("recruit", "211.234.12.34", CHROME_WINDOWS);
        notifier.lockedIp("198.51.100.7", CHROME_WINDOWS);
        notifier.lockedGlobal();
        notifier.close();

        assertThat(attempts).hasSize(3);
        assertThat(output.getOut()).contains("Gate alert failed: IllegalStateException", "Gate alert failed: status=429")
                .doesNotContain("telegram-token-marker", "api.telegram.org");
    }

    @Test
    @DisplayName("[GATE-02] 보내기가 막혀도 요청 스레드는 기다리지 않는다 — 큐(50건)가 차면 버리고 남긴다")
    void neverBlocksAndDropsWhenQueueIsFull(CapturedOutput output) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        GateNotifier notifier = notifier(text -> {
            started.countDown();
            release.await();
            sent.add(text);
            return 200;
        });

        notifier.lockedGlobal();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();   // 첫 알림은 작업자가 꺼내 보내는 중
        long begin = System.nanoTime();
        for (int i = 0; i < GateNotifier.QUEUE_SIZE + 10; i++) {
            notifier.lockedGlobal();
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        release.countDown();
        notifier.close();

        assertThat(elapsedMs).isLessThan(1000);
        assertThat(sent).hasSize(1 + GateNotifier.QUEUE_SIZE);
        assertThat(output.getOut()).contains("Gate alert dropped: queue full");
    }

    @Test
    @DisplayName("[L-4] 알림기·전송부·설정의 toString 은 봇 토큰·대화 ID 를 적지 않는다")
    void toStringHidesSecrets() {
        GateProperties props = new GateProperties(CODES, "", "", null, new GateProperties.Telegram(TOKEN, CHAT));
        GateNotifier notifier = notifier(props, recording());
        GateNotifier.TelegramTransport transport = new GateNotifier.TelegramTransport(GateNotifier.TELEGRAM_API, TOKEN, CHAT,
                JsonMapper.builder().build(), GateNotifier.TIMEOUT);
        transport.close();

        assertThat(List.of(props.toString(), props.telegram().toString(), notifier.toString(), transport.toString()))
                .allSatisfy(s -> assertThat(s).doesNotContain("telegram-token-marker", "123456", "chat-id-marker"));
        assertThat(props.toString()).contains("telegram=Telegram[botToken=****, chatId=****]");
    }

    @Test
    @DisplayName("[GATE-02] HTTP 전송부는 sendMessage 에 평문 JSON(chat_id·text·link_preview_options)을 POST 하고 상태 코드를 돌려준다")
    void httpTransportPostsJson() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            path.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        JsonMapper mapper = JsonMapper.builder().build();
        GateNotifier.TelegramTransport transport = new GateNotifier.TelegramTransport(uri(server), TOKEN, CHAT, mapper,
                GateNotifier.TIMEOUT);
        try {
            int status = transport.send("🔓 SmartCollab — 잠금 해제\n코드: \"r<e>c*\"_");

            JsonNode json = mapper.readTree(body.get());
            assertThat(status).isEqualTo(429);
            assertThat(path.get()).isEqualTo("POST /bot" + TOKEN + "/sendMessage");
            assertThat(contentType.get()).isEqualTo("application/json");
            assertThat(json.get("chat_id").asString()).isEqualTo(CHAT);
            assertThat(json.get("text").asString()).isEqualTo("🔓 SmartCollab — 잠금 해제\n코드: \"r<e>c*\"_");
            assertThat(json.get("link_preview_options").get("is_disabled").asBoolean()).isTrue();   // Bot API 7.0 부터
            assertThat(json.has("disable_web_page_preview")).isFalse();
            assertThat(json.has("parse_mode")).isFalse();
        } finally {
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("[GATE-02] HTTP 전송부는 응답이 없으면 시간 상한에서 끊는다 (운영 5초)")
    void httpTransportTimesOut() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            try {
                hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        GateNotifier.TelegramTransport transport = new GateNotifier.TelegramTransport(uri(server), TOKEN, CHAT,
                JsonMapper.builder().build(), Duration.ofMillis(300));
        try {
            long begin = System.nanoTime();
            assertThatThrownBy(() -> transport.send("x")).isInstanceOf(HttpTimeoutException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).isLessThan(5000);
        } finally {
            hold.countDown();
            transport.close();
            server.stop(0);
        }
    }

    @Test
    @DisplayName("[GATE-02] HTTP 전송부는 응답 헤더 뒤 본문이 멈춰도 시간 상한에서 끊는다 — 작업자 하나가 묶여 뒤 알림이 모두 버려지지 않게")
    void httpTransportBoundsStalledBody() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(200, 100);   // 본문 100바이트라 해 놓고 일부만 보낸 뒤 멈춤
            exchange.getResponseBody().write("{\"ok\":".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                hold.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        GateNotifier.TelegramTransport transport = new GateNotifier.TelegramTransport(uri(server), TOKEN, CHAT,
                JsonMapper.builder().build(), Duration.ofMillis(300));
        try {
            long begin = System.nanoTime();
            assertThatThrownBy(() -> transport.send("x")).isInstanceOf(HttpTimeoutException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).isLessThan(5000);
        } finally {
            hold.countDown();
            transport.close();
            server.stop(0);
        }
    }

    // ── 도우미 ──

    private GateNotifier notifier(GateNotifier.Transport transport) {
        return notifier(new GateProperties(CODES, "", "", null, new GateProperties.Telegram(TOKEN, CHAT)), transport);
    }

    private GateNotifier notifier(GateProperties props, GateNotifier.Transport transport) {
        GateNotifier notifier = GateNotifier.create(props, clock, transport);
        created.add(notifier);
        return notifier;
    }

    private GateNotifier.Transport recording() {
        return text -> {
            sent.add(text);
            return 200;
        };
    }

    /** 작업자가 큐에 든 알림을 모두 보낼 때까지 기다린 뒤 보낸 알림 */
    private List<String> drain(GateNotifier notifier) {
        notifier.close();
        return List.copyOf(sent);
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static URI uri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
