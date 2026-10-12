package com.smartcollab.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 시험용 TCP 중계기. {@link #freeze()} 하면 연결은 열어 둔 채 양쪽에서 오는 바이트를 버립니다 — 네트워크가 소리 없이 끊겨
 * 보낸 요청에 응답이 오지 않는 상황(패킷 유실, RST 없음)을 흉내 냅니다.
 */
public final class FreezableTcpProxy implements AutoCloseable {

    private final ServerSocket server;
    private final String targetHost;
    private final int targetPort;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile boolean frozen;

    public FreezableTcpProxy(String targetHost, int targetPort) throws IOException {
        this(0, targetHost, targetPort);
    }

    /** @param listenPort 0 이면 빈 포트 — 아직 아무도 듣지 않던 포트를 나중에 열 때(늦게 뜨는 DB) 번호를 정해 둡니다 */
    public FreezableTcpProxy(int listenPort, String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.server = new ServerSocket(listenPort);
        Thread.ofPlatform().daemon().name("proxy-accept").start(this::acceptLoop);
    }

    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public int port() {
        return server.getLocalPort();
    }

    public void freeze() {
        frozen = true;
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Socket upstream = new Socket(targetHost, targetPort);
                sockets.add(client);
                sockets.add(upstream);
                pump(client, upstream);
                pump(upstream, client);
            } catch (IOException e) {
                return;
            }
        }
    }

    private void pump(Socket from, Socket to) {
        Thread.ofPlatform().daemon().name("proxy-pump").start(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = from.getInputStream()) {
                OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (!frozen) {
                        out.write(buffer, 0, n);
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // 닫힘
            }
        });
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket s : sockets) {
            s.close();
        }
    }
}
