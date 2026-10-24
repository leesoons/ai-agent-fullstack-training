package com.leesoons.toolruntime;

import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path dataFile;

    public ApprovalStore() {
        this(null);
    }

    /**
     * 可选 JSONL 持久化：传入 dataDir 后落盘 {@code approvals.jsonl}，
     * 重启后恢复未过期审批单；dataDir 为 {@code null} 时保持纯内存。
     */
    public ApprovalStore(Path dataDir) {
        this.dataFile = dataDir == null ? null : dataDir.resolve("approvals.jsonl");
        loadFromDisk();
    }

    /**
     * 预授权：签发与调用参数绑定、带有效期且立即生效的一次性审批凭证。
     */
    public void approve(String approvalId, String userId, String tenantId,
                        String toolName, JsonNode arguments, long ttlSeconds) {
        synchronized (items) {
            Approval approval = new Approval(
                    approvalId, userId, tenantId, toolName,
                    digest(toolName, arguments),
                    System.currentTimeMillis() + ttlSeconds * 1000);
            approval.decide(ApprovalStatus.APPROVED, "system");
            persist(approval);
            items.put(approvalId, approval);
        }
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
        synchronized (items) {
            Approval approval = new Approval(
                    approvalId, userId, tenantId, toolName,
                    digest(toolName, arguments),
                    System.currentTimeMillis() + ttlSeconds * 1000);
            persist(approval);
            items.put(approvalId, approval);
            return approval;
        }
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
            persist(approval);
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
            persist(approval);
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
                persist(approval);
            }
        }
    }

    /**
     * 核销审批：必须已批准、未过期，且与当前用户、租户、工具和参数完全一致。
     *
     * <p>校验通过后以 CAS 语义抢锁（{@code APPROVED → EXECUTING}）并立即收尾为
     * {@code CONSUMED}，两个并发核销只会有一个成功。</p>
     */
    /**
     * 非抛异常版核销：校验通过则以 CAS 语义抢锁并收尾，返回是否核销成功。
     * 供 ToolRuntime 区分「尚未批准（可签发 PENDING 等人工）」与「已用/已拒/参数不符（应拒绝）」。
     */
    public boolean tryConsume(String approvalId, ToolDefinition tool,
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
                return false;
            }
            approval.claim();
            approval.finish(ApprovalStatus.CONSUMED);
            persist(approval);
            return true;
        }
    }

    /**
     * 核销审批：必须已批准、未过期，且与当前用户、租户、工具和参数完全一致。
     */
    public void consume(String approvalId, ToolDefinition tool,
                        JsonNode arguments, ExecutionContext context) {
        if (!tryConsume(approvalId, tool, arguments, context)) {
            throw new PolicyDenied("APPROVAL_REQUIRED", "请确认本次操作的工具与参数，审批可能已失效");
        }
    }

    /**
     * 落盘当前审批单的最新快照；写入失败即抛错，保证「磁盘不超前于内存」的恢复语义。
     */
    private void persist(Approval approval) {
        if (dataFile == null) {
            return;
        }
        ApprovalSnapshot snapshot = new ApprovalSnapshot(
                approval.approvalId(), approval.userId(), approval.tenantId(),
                approval.toolName(), approval.digest(), approval.expiresAtMillis(),
                approval.status(), approval.decidedBy());
        try {
            Files.createDirectories(dataFile.getParent());
            String line = mapper.writeValueAsString(snapshot) + System.lineSeparator();
            Files.writeString(dataFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("审批单落盘失败，关键数据不可静默丢失: " + approval.approvalId(), e);
        }
    }

    private void loadFromDisk() {
        if (dataFile == null || !Files.exists(dataFile)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(dataFile, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                ApprovalSnapshot snapshot = mapper.readValue(line, ApprovalSnapshot.class);
                items.put(snapshot.approvalId(), restore(snapshot));
            }
        } catch (IOException e) {
            throw new IllegalStateException("审批单落盘读取失败: " + dataFile, e);
        }
    }

    /**
     * 从落盘快照重建审批单；{@code EXECUTING} 是进程死亡时的中间态（结果未知），
     * 保守归位为 {@code FAILED}，宁可让用户重新审批也不冒重复执行的风险。
     */
    private Approval restore(ApprovalSnapshot snapshot) {
        Approval approval = new Approval(
                snapshot.approvalId(), snapshot.userId(), snapshot.tenantId(),
                snapshot.toolName(), snapshot.digest(), snapshot.expiresAtMillis());
        switch (snapshot.status()) {
            case PENDING -> { }
            case APPROVED, REJECTED -> approval.decide(snapshot.status(), snapshot.decidedBy());
            case CONSUMED, FAILED -> {
                approval.decide(ApprovalStatus.APPROVED, snapshot.decidedBy());
                approval.claim();
                approval.finish(snapshot.status());
            }
            case EXECUTING -> {
                approval.decide(ApprovalStatus.APPROVED, snapshot.decidedBy());
                approval.finish(ApprovalStatus.FAILED);
            }
        }
        return approval;
    }

    /**
     * 审批单落盘快照：与 {@link Approval} 一一对应，仅用于 JSONL 序列化与恢复。
     */
    private record ApprovalSnapshot(
            String approvalId,
            String userId,
            String tenantId,
            String toolName,
            String digest,
            long expiresAtMillis,
            ApprovalStatus status,
            String decidedBy) {
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
