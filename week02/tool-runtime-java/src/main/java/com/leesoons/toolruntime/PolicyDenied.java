package com.leesoons.toolruntime;

/**
 * 策略拒绝异常：中断不满足白名单、参数、权限、预检或审批条件的调用。
 */
public final class PolicyDenied extends RuntimeException {

    private final String code;

    public PolicyDenied(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
