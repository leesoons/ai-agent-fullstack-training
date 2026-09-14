package com.leesoons.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.leesoons.llmgateway.model.ChatMessage;
import com.leesoons.llmgateway.model.ChatRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 结构化输出降级缓存：只缓存已通过本地 Schema 校验的结果，
 * 键绑定身份、模型、Schema 与请求消息，命中后返回与正常输出同构的响应。
 */
@Component
public class StructuredOutputCache {

    private static final long TTL_MILLIS = 5 * 60 * 1000L;
    private static final int MAX_ENTRIES = 1000;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public String key(String identity, ChatRequest request, JsonNode schema) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, identity);
            update(digest, request.getModel());
            if (schema != null) {
                update(digest, schema.toString());
            }
            if (request.getPrompt() != null) {
                update(digest, request.getPrompt().getId());
                update(digest, String.valueOf(request.getPrompt().getVersion()));
                update(digest, request.getPrompt().getPosition());
                // 变量按 key 排序后参与哈希，保证相同模板不同变量不会误命中
                request.getPrompt().getVariables().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> {
                            update(digest, entry.getKey());
                            update(digest, String.valueOf(entry.getValue()));
                        });
            }
            for (ChatMessage message : request.getMessages()) {
                update(digest, message.getRole());
                update(digest, message.getContent());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public Optional<Entry> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() - entry.createdAt() > TTL_MILLIS) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    public void put(String key, String content, JsonNode parsed) {
        if (entries.size() >= MAX_ENTRIES) {
            entries.entrySet().removeIf(candidate ->
                    System.currentTimeMillis() - candidate.getValue().createdAt() > TTL_MILLIS);
            if (entries.size() >= MAX_ENTRIES) {
                return;
            }
        }
        entries.put(key, new Entry(content, parsed, System.currentTimeMillis()));
    }

    public record Entry(String content, JsonNode parsed, long createdAt) {
    }

    private static void update(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
