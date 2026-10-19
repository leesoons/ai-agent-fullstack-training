package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 业务预检查：在产生副作用前校验资源归属、状态、额度等业务事实。
 *
 * <p>校验失败应抛出 {@link PolicyDenied}，Runtime 会把它归一化为结构化错误；
 * 只有通过预检后，才会继续审批与执行。</p>
 */
@FunctionalInterface
public interface ToolPrecheck {

    void precheck(JsonNode arguments, ExecutionContext context) throws Exception;
}
