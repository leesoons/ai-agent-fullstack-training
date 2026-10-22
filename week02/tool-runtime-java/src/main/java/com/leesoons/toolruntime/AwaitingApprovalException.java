package com.leesoons.toolruntime;

/**
 * 工具需要审批、但当前尚无有效批准时的暂停信号：携带待人工决定的审批单 id。
 *
 * <p>与 {@link PolicyDenied} 不同，它不代表「拒绝执行」，而是「先停下等人」；
 * {@link ToolRuntime} 会把它归一化为 {@code AWAITING_APPROVAL} 结果，由 Agent Loop 熔断本轮。</p>
 */
public final class AwaitingApprovalException extends RuntimeException {

    private final String approvalId;

    public AwaitingApprovalException(String approvalId) {
        super("工具调用正在等待人工审批: " + approvalId);
        this.approvalId = approvalId;
    }

    public String approvalId() {
        return approvalId;
    }
}
