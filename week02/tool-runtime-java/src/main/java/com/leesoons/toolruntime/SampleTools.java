package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * 示例工具集：注册天气查询与客户查询，覆盖不同风险、依赖与参数约束。
 */
public final class SampleTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SampleTools() {
    }

    /**
     * 构建示例工具注册表：通过插件管理器加载，演示插件化注册。
     */
    public static ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        PluginManager manager = new PluginManager(new PluginContext(registry));
        manager.load(new WeatherToolsPlugin());
        manager.load(new CustomerDatabaseToolsPlugin());
        return registry;
    }

    public static ToolDefinition weatherTool() {
        return new ToolDefinition(
                "query_weather",
                "1.0.0",
                "查询指定城市的当前示例天气",
                schema("""
                        {"type":"object",
                         "properties":{
                           "city":{"type":"string","minLength":1,"maxLength":40},
                           "unit":{"type":"string","enum":["celsius","fahrenheit"]}},
                         "required":["city"],
                         "additionalProperties":false}
                        """),
                schema("""
                        {"type":"object",
                         "properties":{
                           "city":{"type":"string"},
                           "condition":{"type":"string"},
                           "temperature":{"type":"number"},
                           "unit":{"type":"string","enum":["celsius","fahrenheit"]}},
                         "required":["city","condition","temperature","unit"],
                         "additionalProperties":false}
                        """),
                SampleTools::queryWeather,
                "weather:read",
                RiskLevel.LOW,
                3.0,
                2,
                true,
                Set.of("weather-service"));
    }

    public static ToolDefinition customerDatabaseTool() {
        return new ToolDefinition(
                "query_customer_database",
                "1.0.0",
                "按客户编号查询已授权的客户摘要信息",
                schema("""
                        {"type":"object",
                         "properties":{
                           "customer_id":{"type":"string","minLength":3,"maxLength":40,"pattern":"^[A-Za-z0-9_-]+$"}},
                         "required":["customer_id"],
                         "additionalProperties":false}
                        """),
                schema("""
                        {"type":"object",
                         "properties":{
                           "customer_id":{"type":"string"},
                           "name":{"type":"string"},
                           "membership":{"type":"string","enum":["basic","gold","platinum"]},
                           "order_count":{"type":"integer","minimum":0}},
                         "required":["customer_id","name","membership","order_count"],
                         "additionalProperties":false}
                        """),
                SampleTools::queryCustomerDatabase,
                "database:read",
                RiskLevel.MEDIUM,
                3.0,
                1,
                true,
                Set.of("customer-database"));
    }

    private static JsonNode queryWeather(JsonNode params, ExecutionContext context) {
        String city = params.path("city").asText();
        String unit = params.path("unit").asText("celsius");
        double temperature;
        String condition;
        switch (city) {
            case "北京" -> { temperature = 26.0; condition = "晴"; }
            case "上海" -> { temperature = 29.0; condition = "多云"; }
            case "深圳" -> { temperature = 31.0; condition = "小雨"; }
            default -> { temperature = 22.0; condition = "晴间多云"; }
        }
        if ("fahrenheit".equals(unit)) {
            temperature = Math.round(temperature * 9 / 5 + 32 * 10) / 10.0;
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("city", city);
        out.put("condition", condition);
        out.put("temperature", temperature);
        out.put("unit", unit);
        return out;
    }

    private static JsonNode queryCustomerDatabase(JsonNode params, ExecutionContext context) {
        String customerId = params.path("customer_id").asText();
        ObjectNode out = MAPPER.createObjectNode();
        out.put("customer_id", customerId);
        switch (customerId) {
            case "cust-1001" -> {
                out.put("name", "王小明");
                out.put("membership", "gold");
                out.put("order_count", 12);
            }
            case "cust-1002" -> {
                out.put("name", "李小红");
                out.put("membership", "platinum");
                out.put("order_count", 28);
            }
            default -> {
                out.put("name", "未登记客户");
                out.put("membership", "basic");
                out.put("order_count", 0);
            }
        }
        return out;
    }

    private static JsonNode schema(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("invalid embedded schema", e);
        }
    }
}
