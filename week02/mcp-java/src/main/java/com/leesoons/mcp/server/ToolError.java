package com.leesoons.mcp.server;

/**
 * 工具业务错误：对应 Python 示例里的 {@code raise ToolError("ORDER_NOT_FOUND")}。
 *
 * <p>{@link McpServer} 捕获本异常后，统一包装成 {@code isError=true} 的结果。</p>
 */
public final class ToolError extends RuntimeException {

    private final String code;

    public ToolError(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
