package com.leesoons.toolruntime;

import java.util.List;
import java.util.Set;

/**
 * 转账工具插件：注册 transfer，把资金写操作接入治理链路。
 *
 * <p>转账为内存模拟，无外部 service 依赖，故 {@code requires} 为空。</p>
 */
public final class TransferToolsPlugin implements ToolPlugin {

    @Override
    public String name() {
        return "transfer-tools";
    }

    @Override
    public Set<String> requires() {
        return Set.of();
    }

    @Override
    public List<Dispose> apply(PluginContext context) {
        return List.of(context.registerTool(TransferTool.definition()));
    }
}
