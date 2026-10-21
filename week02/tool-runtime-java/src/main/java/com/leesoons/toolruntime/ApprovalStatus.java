package com.leesoons.toolruntime;

/**
 * 审批单状态机：{@code PENDING → APPROVED/REJECTED → EXECUTING → CONSUMED/FAILED}。
 *
 * <p>每一次跃迁都由 {@link ApprovalStore} 在锁内以「当前状态」为条件推进（CAS），
 * 防止两个执行者同时消费同一张审批单。</p>
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXECUTING,
    CONSUMED,
    FAILED
}
