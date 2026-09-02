package com.leesoons.llmgateway.model;

/**
 * GET /v1/models 返回的模型别名信息。
 */
public class ModelInfo {

    private String id;
    private String object = "model";
    private long created;
    private String ownedBy = "llm-gateway-java";
    private String protocol;

    public ModelInfo() {
    }

    public ModelInfo(String id, long created, String protocol) {
        this.id = id;
        this.created = created;
        this.protocol = protocol;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public String getOwnedBy() {
        return ownedBy;
    }

    public void setOwnedBy(String ownedBy) {
        this.ownedBy = ownedBy;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }
}
