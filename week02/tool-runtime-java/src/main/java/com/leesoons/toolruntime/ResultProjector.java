package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工具结果保护：对不可信内容脱敏并限制规模，防止敏感数据泄露或挤占模型上下文。
 */
public final class ResultProjector {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "token|secret|password|authorization|api_?key|credential|cookie|session|private_?key|access_?key",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}");

    private static final int MAX_DEPTH = 8;
    private static final int MAX_ITEMS = 50;
    private static final int MAX_STRING_LENGTH = 2_000;

    private ResultProjector() {
    }

    /**
     * 递归脱敏：敏感键置为 {@code ***}，邮箱置为 {@code ***@***}。
     */
    public static JsonNode redact(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode() && !node.isTextual()) {
            return node;
        }
        if (node.isTextual()) {
            String text = EMAIL.matcher(node.textValue()).replaceAll("***@***");
            return NODES.textNode(text);
        }
        if (node.isArray()) {
            ArrayNode copy = node.deepCopy();
            for (int i = 0; i < copy.size(); i++) {
                copy.set(i, redact(copy.get(i)));
            }
            return copy;
        }
        if (node.isObject()) {
            ObjectNode copy = node.deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = copy.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (SENSITIVE_KEY.matcher(field.getKey()).find()) {
                    field.setValue(NODES.textNode("***"));
                } else {
                    field.setValue(redact(field.getValue()));
                }
            }
            return copy;
        }
        return node.deepCopy();
    }

    /**
     * 投影给模型：限制深度、条目数与字符串长度，并在返回前脱敏。
     */
    public static JsonNode projectForModel(JsonNode node) {
        return projectForModel(node, 0);
    }

    private static JsonNode projectForModel(JsonNode node, int depth) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (depth >= MAX_DEPTH) {
            return NODES.textNode("[内容嵌套过深，已截断]");
        }
        if (node.isObject()) {
            ObjectNode projected = NODES.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            int count = 0;
            while (fields.hasNext() && count < MAX_ITEMS) {
                Map.Entry<String, JsonNode> field = fields.next();
                projected.set(field.getKey(), projectForModel(field.getValue(), depth + 1));
                count++;
            }
            if (fields.hasNext()) {
                projected.put("_truncated", "[字段过多，已截断]");
            }
            return redact(projected);
        }
        if (node.isArray()) {
            ArrayNode projected = NODES.arrayNode();
            for (int i = 0; i < Math.min(node.size(), MAX_ITEMS); i++) {
                projected.add(projectForModel(node.get(i), depth + 1));
            }
            if (node.size() > MAX_ITEMS) {
                projected.add("[项目过多，已截断]");
            }
            return redact(projected);
        }
        if (node.isTextual()) {
            String text = node.textValue();
            if (text.length() > MAX_STRING_LENGTH) {
                text = text.substring(0, MAX_STRING_LENGTH) + "[文本过长，已截断]";
            }
            return redact(NODES.textNode(text));
        }
        return node.deepCopy();
    }
}
