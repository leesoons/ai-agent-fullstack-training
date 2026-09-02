package com.leesoons.llmgateway.adapter;

import com.leesoons.llmgateway.model.TokenUsage;

/**
 * Adapter 归一化后的流式事件帧。
 */
public class AdapterStreamFrame {

    public enum Kind {
        DELTA,
        COMPLETE,
        ERROR
    }

    private final Kind kind;
    private final String delta;
    private final TokenUsage usage;
    private final String error;

    private AdapterStreamFrame(Kind kind, String delta, TokenUsage usage, String error) {
        this.kind = kind;
        this.delta = delta;
        this.usage = usage;
        this.error = error;
    }

    public static AdapterStreamFrame delta(String text) {
        return new AdapterStreamFrame(Kind.DELTA, text, null, null);
    }

    public static AdapterStreamFrame complete(TokenUsage usage) {
        return new AdapterStreamFrame(Kind.COMPLETE, null, usage, null);
    }

    public static AdapterStreamFrame error(String message) {
        return new AdapterStreamFrame(Kind.ERROR, null, null, message);
    }

    public Kind getKind() {
        return kind;
    }

    public String getDelta() {
        return delta;
    }

    public TokenUsage getUsage() {
        return usage;
    }

    public String getError() {
        return error;
    }
}
