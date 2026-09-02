package com.leesoons.llmgateway.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 单独渲染 Prompt 的请求体。
 */
public class PromptRender {

    private Integer version;
    private Map<String, Object> variables = new HashMap<>();

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables;
    }
}
