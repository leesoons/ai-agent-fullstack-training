package com.leesoons.llmgateway.service;

import com.leesoons.llmgateway.core.GatewayErrorCode;
import com.leesoons.llmgateway.core.GatewayException;
import com.leesoons.llmgateway.model.PromptCreate;
import com.leesoons.llmgateway.model.PromptRecord;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 版本管理：创建、查询、变量替换渲染。
 * 使用 Pebble 引擎兼容 Jinja 风格的 {{ variable }} 语法，并启用严格变量校验。
 */
@Service
public class PromptService {

    public static final String DEMO_PROMPT_ID = "agent_code_reviewer";

    private final PromptStore store;
    private final PebbleEngine engine;

    public PromptService(PromptStore store) {
        this.store = store;
        this.engine = new PebbleEngine.Builder().strictVariables(true).build();
    }

    @PostConstruct
    void seedDemoPrompt() {
        try {
            store.get(DEMO_PROMPT_ID, null);
        } catch (GatewayException e) {
            PromptCreate demo = new PromptCreate();
            demo.setId(DEMO_PROMPT_ID);
            demo.setName("代码审查助手");
            demo.setRole("system");
            demo.setDescription("演示模板：验证变量替换与版本引用");
            demo.setContent("""
                    你是{{ language }}代码审查助手，请重点关注{{ focus }}。
                    审查结论只输出 JSON，字段为 summary 与 score。
                    """);
            demo.setActivate(true);
            store.createVersion(demo);
        }
    }

    public PromptRecord createVersion(PromptCreate create) {
        return store.createVersion(create);
    }

    public PromptRecord get(String id, Integer version) {
        return store.get(id, version);
    }

    public List<PromptRecord> list() {
        return store.list();
    }

    public RenderedPrompt render(String id, Map<String, Object> variables, Integer version) {
        PromptRecord prompt = store.get(id, version);
        String content;
        try {
            PebbleTemplate template = engine.getLiteralTemplate(prompt.getContent());
            StringWriter writer = new StringWriter();
            template.evaluate(writer, variables == null ? new HashMap<>() : new HashMap<>(variables));
            content = writer.toString();
        } catch (Exception e) {
            throw new GatewayException("Prompt rendering failed: " + e.getMessage(),
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request_error",
                    GatewayErrorCode.PROMPT_RENDER_ERROR, "prompt");
        }
        return new RenderedPrompt(prompt, content);
    }

    public record RenderedPrompt(PromptRecord prompt, String content) {
    }
}
