package com.leesoons.toolruntime;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批凭证存储与核销组件：确保高风险操作使用匹配、未使用且未过期的授权。
 */
public final class ApprovalStore {

    private final Map<String, Approval> items = new LinkedHashMap<>();

    /**
     * 签发与调用参数绑定、带有效期的一次性审批凭证。
     */
    public void approve(String approvalId, String userId, String tenantId,
                        String toolName, JsonNode arguments, long ttlSeconds) {
        items.put(approvalId, new Approval(
                approvalId,
                userId,
                tenantId,
                toolName,
                digest(toolName, arguments),
                System.currentTimeMillis() + ttlSeconds * 1000));
    }

    /**
     * 默认 300 秒有效期。
     */
    public void approve(String approvalId, String userId, String tenantId,
                        String toolName, JsonNode arguments) {
        approve(approvalId, userId, tenantId, toolName, arguments, 300);
    }

    /**
     * 核销审批：必须未使用、未过期，且与当前用户、租户、工具和参数完全一致。
     */
    public void consume(String approvalId, ToolDefinition tool,
                        JsonNode arguments, ExecutionContext context) {
        Approval approval = items.get(approvalId == null ? "" : approvalId);
        boolean valid = approval != null
                && !approval.used()
                && approval.expiresAtMillis() >= System.currentTimeMillis()
                && approval.userId().equals(context.userId())
                && approval.tenantId().equals(context.tenantId())
                && approval.toolName().equals(tool.name())
                && approval.digest().equals(digest(tool.name(), arguments));
        if (!valid) {
            throw new PolicyDenied("APPROVAL_REQUIRED", "请确认本次操作的工具与参数，审批可能已失效");
        }
        approval.markUsed();
    }

    /**
     * 工具名 + 规范化参数摘要；键顺序不同不会改变摘要，审批不能复用到不同参数。
     */
    static String digest(String toolName, JsonNode arguments) {
        String canonical = canonical(arguments);
        return sha256Hex(toolName + ":" + canonical);
    }

    /**
     * 递归规范化 JSON：对象字段按键排序，数组保持顺序，标量走 Jackson 的标准序列化。
     */
    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            StringBuilder builder = new StringBuilder("{");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                String name = names.get(i);
                builder.append(JsonStringEncoder.getInstance().quoteAsString(name))
                        .append(':')
                        .append(canonical(node.get(name)));
            }
            return builder.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(canonical(node.get(i)));
            }
            return builder.append(']').toString();
        }
        return node.toString();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
