package com.leesoons.toolruntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计输出端口：记录调用链路、主体、风险、结果与耗时等可观测信息。
 */
public final class AuditSink {

    private final List<Map<String, Object>> records = new ArrayList<>();

    public void write(Map<String, Object> record) {
        records.add(new LinkedHashMap<>(record));
    }

    public List<Map<String, Object>> records() {
        return List.copyOf(records);
    }
}
