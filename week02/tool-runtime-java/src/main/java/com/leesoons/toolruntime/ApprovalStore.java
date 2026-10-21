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
 *
 * <p>同时支持两种用法：</p>
 * <ul>
 *   <li>预授权：{@link #approve} 签发即 {@code APPROVED}，随后 {@link #consume} 一次性核销</li>
 *   <li>可恢复审批：{@link #createApproval} 签发 {@code PENDING}，人工 {@link #decide} 后
 *       {@link #claim} 抢锁、{@link #finish} 收尾，可跨进程/重启恢复执行</li>
 * </ul>
 */
public final class ApprovalStore {

    private final Map<String, Approval> items = new LinkedHashMap<>();

    /**
     * 预授权：签发与调用参数绑定、带有效期且立即生效的一次性审批凭证。
     */
    public void approve(String approvalId, String userId, String tenantId,
                        String toolName, JsonNode arguments, long ttlSeconds) {
        Approval approval = new Approval(
                approvalId, userId, tenantId, toolName,
                digest(toolName, arguments),
                System.currentTimeMillis() + ttlSeconds * 1000);
        approval.decide(ApprovalStatus.APPROVED, "system");
        items.put(approvalId, approval);
    }

    /**
     * 默认 300 秒有效期。
     */
    public void approve(String approvalId, String userId, String tenantId,
                        String toolName, JsonNode arguments) {
        approve(approvalId, userId, tenantId, toolName, arguments, 300);
    }

    /**
     * 签发可恢复审批单（{@code PENDING}），等待人工决定。
     */
    public Approval createApproval(String approvalId, String userId, String tenantId,
                                   String toolName, JsonNode arguments, long ttlSeconds) {
        Approval approval = new Approval(
                approvalId, userId, tenantId, toolName,
                digest(toolName, arguments),
                System.currentTimeMillis() + ttlSeconds * 1000);
        items.put(approvalId, approval);
        return approval;
    }

    /**
     * 读取审批单（不存在时返回 {@code null}），供观测与测试使用。
     */
    public Approval getApproval(String approvalId) {
        synchronized (items) {
            return items.get(approvalId);
        }
    }

    /**
     * 人工决定：仅 {@code PENDING} 且未过期时可推进，否则视为缺失/已决定。
     */
    public Approval decide(String approvalId, boolean approve, String actor) {
        synchronized (items) {
            Approval approval = items.get(approvalId);
            if (approval == null || approval.status() != ApprovalStatus.PENDING
                    || approval.expiresAtMillis() < System.currentTimeMillis()) {
                throw new IllegalStateException("Approval is missing, expired, or already decided");
            }
            approval.decide(approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED, actor);
            return approval;
        }
    }

    /**
     * 抢锁：仅 {@code APPROVED} 且未过期时可推进到 {@code EXECUTING}，返回是否抢锁成功。
     */
    public boolean claim(String approvalId) {
        synchronized (items) {
            Approval approval = items.get(approvalId);
            if (approval == null || approval.status() != ApprovalStatus.APPROVED
                    || approval.expiresAtMillis() < System.currentTimeMillis()) {
                return false;
            }
            approval.claim();
            return true;
        }
    }

    /**
     * 收尾：仅 {@code EXECUTING} 可推进到 {@code CONSUMED}/{@code FAILED}。
     */
    public void finish(String approvalId, boolean success, String errorCode) {
        synchronized (items) {
            Approval approval = items.get(approvalId);
            if (approval != null && approval.status() == ApprovalStatus.EXECUTING) {
                approval.finish(success ? ApprovalStatus.CONSUMED : ApprovalStatus.FAILED);
            }
        }
    }

    /**
     * 核销审批：必须已批准、未过期，且与当前用户、租户、工具和参数完全一致。
     *
     * <p>校验通过后以 CAS 语义抢锁（{@code APPROVED → EXECUTING}）并立即收尾为
     * {@code CONSUMED}，两个并发核销只会有一个成功。</p>
     */
    public void consume(String approvalId, ToolDefinition tool,
                        JsonNode arguments, ExecutionContext context) {
        synchronized (items) {
            Approval approval = items.get(approvalId == null ? "" : approvalId);
            boolean valid = approval != null
                    && approval.status() == ApprovalStatus.APPROVED
                    && approval.expiresAtMillis() >= System.currentTimeMillis()
                    && approval.userId().equals(context.userId())
                    && approval.tenantId().equals(context.tenantId())
                    && approval.toolName().equals(tool.name())
                    && approval.digest().equals(digest(tool.name(), arguments));
            if (!valid) {
                throw new PolicyDenied("APPROVAL_REQUIRED", "请确认本次操作的工具与参数，审批可能已失效");
            }
            approval.claim();
            approval.finish(ApprovalStatus.CONSUMED);
        }
    }

    /**
     * 工具名 + 规范化参数摘要；键顺序不同不会改变摘要，审批不能复用到不同参数。
     */
    static String digest(String toolName, JsonNode arguments) {
        return sha256Hex(toolName + ":" + canonical(arguments));
    }

    /**
     * 递归规范化 JSON：对象字段按键排序，数组保持顺序，标量走 Jackson 的标准序列化。
     */
    static String canonical(JsonNode node) {
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
