package com.leesoons.toolruntime;

/**
 * 一次性审批凭证：绑定身份、租户、目标工具、参数摘要与有效期。
 */
public final class Approval {

    private final String approvalId;
    private final String userId;
    private final String tenantId;
    private final String toolName;
    private final String digest;
    private final long expiresAtMillis;
    private boolean used;

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

    public boolean used() {
        return used;
    }

    public void markUsed() {
        this.used = true;
    }
}
