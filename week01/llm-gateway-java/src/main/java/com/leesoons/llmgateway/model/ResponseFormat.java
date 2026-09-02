package com.leesoons.llmgateway.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 结构化输出约束。统一使用 json_schema 语义，由各 Adapter 映射到协议原生能力。
 */
public class ResponseFormat {

    private String type = "json_schema";
    private String name;
    private boolean strict = true;
    private JsonNode schema;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isStrict() {
        return strict;
    }

    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    public JsonNode getSchema() {
        return schema;
    }

    public void setSchema(JsonNode schema) {
        this.schema = schema;
    }

    public boolean isJsonSchema() {
        return "json_schema".equalsIgnoreCase(type) && schema != null;
    }
}
