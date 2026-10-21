package com.leesoons.toolruntime;

/**
 * 一次性审批凭证：绑定身份、租户、目标工具、参数摘要与有效期，并按状态机推进。
 *
 * <p>{@code PENDING}（待人工决定）→ {@code APPROVED}/{@code REJECTED} → 执行时
 * {@code EXECUTING} → 收尾 {@code CONSUMED}/{@code FAILED}。状态跃迁只能由
 * {@link ApprovalStore} 在锁内以当前状态为条件推进。</p>
 */
public final class Approval {

    private final String approvalId;
    private final String userId;
    private final String tenantId;
    private final String toolName;
    private final String digest;
    private final long expiresAtMillis;
    private ApprovalStatus status = ApprovalStatus.PENDING;
    private String decidedBy;

    public Approval(String approvalId, String userId, String tenantId,
                    String toolName, String digest, long expiresAtMillis) {
        this.approvalId = approvalId;
        this.userId = userId;
        this.tenantId = tenantId;
        this.toolName = toolName;
        this.digest = digest;
        this.expiresAtMillis = expiresAtMillis;
    }

    public String approvalId() {
        return approvalId;
    }

    public String userId() {
        return userId;
    }

    public String tenantId() {
        return tenantId;
    }

    public String toolName() {
        return toolName;
    }

    public String digest() {
        return digest;
    }

    public long expiresAtMillis() {
        return expiresAtMillis;
    }

    public ApprovalStatus status() {
        return status;
    }

    public String decidedBy() {
        return decidedBy;
    }

    /**
     * 兼容旧语义：是否已被消费。
     */
    public boolean used() {
        return status == ApprovalStatus.CONSUMED;
    }

    void decide(ApprovalStatus target, String actor) {
        this.status = target;
        this.decidedBy = actor;
    }

    void claim() {
        this.status = ApprovalStatus.EXECUTING;
    }

    void finish(ApprovalStatus target) {
        this.status = target;
    }
}
