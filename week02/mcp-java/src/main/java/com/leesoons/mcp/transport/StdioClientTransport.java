package com.leesoons.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MCP 的 stdio 客户端传输：用 {@link ProcessBuilder} 启动 Server 子进程，
 * 通过子进程的 stdin/stdout 逐行交换 JSON（newline-delimited JSON）。
 *
 * <p>分帧规则：每条消息序列化成单行紧凑 JSON，内部不允许裸换行，
 * 行尾 {@code \n} 即消息边界。</p>
 *
 * <p>关联规则：请求带 {@code id}，响应按 {@code id} 回到对应的
 * {@link CompletableFuture}，因此多个请求可以并发在飞而不串线。</p>
 */
public final class StdioClientTransport implements AutoCloseable {

    private final ProcessBuilder processBuilder;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private Process process;
    private BufferedWriter writer;
    private volatile boolean running;

    public StdioClientTransport(List<String> command) {
        this.processBuilder = new ProcessBuilder(command);
    }

    /**
     * 启动 Server 子进程，并开启后台读线程，把每一条响应按 {@code id} 投递回去。
     */
    public void start() throws IOException {
        process = processBuilder.start();
        writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        running = true;
        Thread.ofVirtual().start(() -> readLoop(reader));
    }

    private void readLoop(BufferedReader reader) {
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                JsonNode node = mapper.readTree(line);
                Long id = node.hasNonNull("id") ? node.get("id").asLong() : null;
                if (id == null) {
                    // 服务端通知（无 id），演示阶段直接忽略。
                    continue;
                }
                CompletableFuture<JsonNode> future = pending.remove(id);
                if (future != null) {
                    future.complete(node);
                }
            }
        } catch (Exception e) {
            pending.values().forEach(future -> future.completeExceptionally(e));
        }
    }

    /**
     * 同步发送一条带 {@code id} 的请求，阻塞等待对应响应。
     */
    public JsonNode send(long id, String method, JsonNode params, long timeoutMillis) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        write(request);
        return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 发送一条通知（无 {@code id}），服务端不回响应。
     */
    public void notify(String method, JsonNode params) throws IOException {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        write(request);
    }

    private void write(ObjectNode node) throws IOException {
        writer.write(mapper.writeValueAsString(node));
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() {
        running = false;
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }
}
