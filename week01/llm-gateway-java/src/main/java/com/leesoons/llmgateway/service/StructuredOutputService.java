package com.leesoons.llmgateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leesoons.llmgateway.core.StructuredOutputException;
import com.leesoons.llmgateway.model.ChatRequest;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化输出：JSON Schema 提取、fence 清理、本地解析与校验、修复指令生成。
 */
@Service
public class StructuredOutputService {

    private static final Pattern FENCE = Pattern.compile(
            "^\\s*```(?:json)?\\s*(.*?)\\s*```\\s*$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public StructuredOutputService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode schemaFrom(ChatRequest request) {
        if (request.getResponseFormat() == null || !request.getResponseFormat().isJsonSchema()) {
            return null;
        }
        return request.getResponseFormat().getSchema();
    }

    public String instructionFor(JsonNode schema) {
        return "You must respond with a single valid JSON object that matches the following JSON Schema. "
                + "Return only the JSON, with no Markdown fences or explanation.\n"
                + schema.toString();
    }

    public JsonNode validate(String content, JsonNode schema) {
        if (content == null || content.isBlank()) {
            throw new StructuredOutputException("Could not find textual model output to validate", null);
        }
        String cleaned = stripFences(content);
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(cleaned);
        } catch (JsonProcessingException e) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("line", e.getLocation() == null ? null : e.getLocation().getLineNr());
            details.put("column", e.getLocation() == null ? null : e.getLocation().getColumnNr());
            details.put("message", e.getOriginalMessage());
            throw new StructuredOutputException("Model output is not valid JSON", details);
        }

        JsonSchema jsonSchema = schemaFactory.getSchema(schema);
        Set<ValidationMessage> messages = jsonSchema.validate(parsed);
        if (!messages.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("issues", messages.stream()
                    .map(ValidationMessage::getMessage)
                    .toList());
            throw new StructuredOutputException("Model output does not match the requested JSON Schema", details);
        }
        return parsed;
    }

    public String repairInstruction(StructuredOutputException error, JsonNode schema) {
        String details;
        try {
            details = objectMapper.writeValueAsString(error.getDetails());
        } catch (JsonProcessingException e) {
            details = String.valueOf(error.getDetails());
        }
        return "Your previous response failed JSON Schema validation. Return only corrected JSON, "
                + "with no Markdown fences or explanation.\n"
                + "Validation error: " + error.getMessage() + "; details=" + details + "\n"
                + "Required schema: " + schema;
    }

    private String stripFences(String content) {
        Matcher matcher = FENCE.matcher(content.trim());
        if (matcher.find()) {
            return matcher.group(1);
        }
        return content.trim();
    }
}
