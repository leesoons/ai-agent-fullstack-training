package com.leesoons.llmgateway.service;

/**
 * 跨层调用关联信息。run_id 标识一次 Agent Run，step_id 标识 Run 内一次循环步骤。
 * call_id 由 Gateway 在每次上游调用时生成并写入用量账本。
 */
public record TraceContext(String runId, String stepId) {

    public static TraceContext none() {
        return new TraceContext(null, null);
    }
}
