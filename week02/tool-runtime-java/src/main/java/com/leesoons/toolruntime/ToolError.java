package com.leesoons.toolruntime;

import java.util.List;

/**
 * 工具执行的结构化错误。稳定 code 驱动 Agent Loop 判断下一步。
 */
public record ToolError(String code, String message, List<String> details) {

    public static ToolError of(String code, String message) {
        return new ToolError(code, message, List.of());
    }
}
