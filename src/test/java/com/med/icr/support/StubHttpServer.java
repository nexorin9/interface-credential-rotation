package com.med.icr.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地 HTTP 替身：只在 src/test 存在，生产代码里没有任何本地默认地址。
 * 按请求顺序返回预置应答，用完应答后落回默认应答；同时记录每个请求的方法 / 路径 / 头 / 体。
 */
public final class StubHttpServer implements AutoCloseable {
    public record Reply(int status, String body) {}
    public record Request(String method, String uri, String headers, String body) {}

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<Reply> replies = new ConcurrentLinkedDeque<>();
    private volatile Reply defaultReply = new Reply(200, "{}");

    public StubHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] in = ex.getRequestBody().readAllBytes();
        // HttpHeaders.toString() 只输出对象哈希，必须逐项拼装才能核对鉴权位与幂等键
        StringBuilder headers = new StringBuilder();
        ex.getRequestHeaders().forEach((name, values) ->
                headers.append(name).append('=').append(values).append(';'));
        requests.add(new Request(ex.getRequestMethod(), ex.getRequestURI().toString(),
                headers.toString(), new String(in, StandardCharsets.UTF_8)));
        Reply reply = replies.pollFirst();
        if (reply == null) reply = defaultReply;
        byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) ex.getResponseBody().write(out);
        ex.close();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public StubHttpServer setDefault(int status, String body) {
        this.defaultReply = new Reply(status, body);
        return this;
    }

    public StubHttpServer enqueue(int status, String body) {
        replies.addLast(new Reply(status, body));
        return this;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public Request lastRequest() {
        return requests.isEmpty() ? null : requests.get(requests.size() - 1);
    }

    public int requestCount() {
        return requests.size();
    }

    @Override public void close() {
        server.stop(0);
    }
}
