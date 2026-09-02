package com.leesoons.llmgateway.model;

import jakarta.validation.constraints.NotBlank;

import java.util.HashMap;
import java.util.Map;

/**
 * 请求中对 Prompt 模板的引用，支持版本指定与变量替换。
 */
public class PromptReference {

    @NotBlank(message = "prompt.id must not be blank")
    private String id;

    private Integer version;
    private Map<String, Object> variables = new HashMap<>();
    private String position = "prepend";

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

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

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }
}
