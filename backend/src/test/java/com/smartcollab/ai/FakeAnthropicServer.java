package com.smartcollab.ai;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Anthropic API 를 흉내 내는 로컬 HTTP 서버 — 실제 SDK 가 만든 요청을 기록하고, 정해 둔 응답(일반 JSON 또는 SSE 스트림)을 돌려줍니다.
 * 실제 API(과금)는 부르지 않습니다.
 */
final class FakeAnthropicServer implements AutoCloseable {

    record Recorded(String path, String apiKey, String body) {
    }

    /** @param gapMillis 0 보다 크면 SSE 이벤트 사이마다 이만큼 쉬며 나눠 보냄(느린 스트림) */
    record Reply(int status, String contentType, String body, long gapMillis) {
        static Reply json(String body) {
            return new Reply(200, "application/json", body, 0);
        }

        static Reply sse(String body) {
            return new Reply(200, "text/event-stream", body, 0);
        }

        /** 이벤트마다 gapMillis 쉬며 보내는 스트림 — 오래 걸리지만 살아 있는 응답, 또는 멈춘 응답 */
        static Reply slowSse(String body, long gapMillis) {
            return new Reply(200, "text/event-stream", body, gapMillis);
        }

        static Reply error(int status, String type, String message) {
            return new Reply(status, "application/json",
                    "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}", 0);
        }
    }

    private final HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    final List<Recorded> requests = new CopyOnWriteArrayList<>();

    FakeAnthropicServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Recorded(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("x-api-key"), body));
            Reply reply;
            synchronized (replies) {
                reply = replies.isEmpty() ? Reply.error(500, "api_error", "no reply queued") : replies.poll();
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", reply.contentType());
            exchange.getResponseHeaders().add("request-id", "req_test");
            if (reply.gapMillis() <= 0) {
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
                return;
            }
            exchange.sendResponseHeaders(reply.status(), 0);   // 길이를 미리 알리지 않고 조금씩
            try (OutputStream out = exchange.getResponseBody()) {
                for (String event : reply.body().split("(?<=\\n\\n)")) {
                    out.write(event.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(reply.gapMillis());
                }
            } catch (IOException | InterruptedException e) {
                // 클라이언트가 먼저 끊음(시간 초과 시험)
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    FakeAnthropicServer reply(Reply reply) {
        synchronized (replies) {
            replies.add(reply);
        }
        return this;
    }

    /** 생각 블록 하나 뒤에 텍스트를 나눠 보내는 스트림 (Haiku 5.5 는 생각이 기본으로 켜져 있음) */
    static String stream(String stopReason, long inputTokens, long outputTokens, String... textChunks) {
        StringBuilder sse = new StringBuilder();
        event(sse, "message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-haiku-5-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":" + inputTokens + ",\"output_tokens\":1}}}");
        event(sse, "content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}");
        event(sse, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig\"}}");
        event(sse, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
        event(sse, "content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        for (String chunk : textChunks) {
            event(sse, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":"
                    + quote(chunk) + "}}");
        }
        event(sse, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");
        event(sse, "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null},"
                + "\"usage\":{\"output_tokens\":" + outputTokens + "}}");
        event(sse, "message_stop", "{\"type\":\"message_stop\"}");
        return sse.toString();
    }

    /** 일반(스트리밍 아님) 응답 — 생각 블록 뒤에 텍스트 하나 */
    static String message(String stopReason, String text, long inputTokens, long outputTokens, String stopDetails) {
        String content = text == null ? "[]"
                : "[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"},{\"type\":\"text\",\"text\":" + quote(text) + "}]";
        return "{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\",\"content\":" + content
                + ",\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null"
                + (stopDetails == null ? "" : ",\"stop_details\":" + stopDetails)
                + ",\"usage\":{\"input_tokens\":" + inputTokens + ",\"output_tokens\":" + outputTokens + "}}";
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static void event(StringBuilder sse, String name, String data) {
        sse.append("event: ").append(name).append('\n').append("data: ").append(data).append("\n\n");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
