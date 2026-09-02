package com.leesoons.llmgateway.model;

/**
 * 网关统一 SSE 事件。type 固定为 meta / delta / done / error。
 */
public class StreamEvent {

    private String type;
    private Object data;

    public StreamEvent() {
    }

    public StreamEvent(String type, Object data) {
        this.type = type;
        this.data = data;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
}
