package com.leesoons.toolruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 转账工具：Week02 作业二的资金写操作示例。
 *
 * <p>把「参数校验 → 业务预检 → 权限判断 → 人工审批 → 超时处理 → 结果脱敏 → 审计追踪」
 * 整条治理链路接进一个真实写工具：转出账户扣款、转入账户入账。</p>
 *
 * <p>业务规则（对齐 Python 作业）：
 * 金额区间拦截 50000 &lt; amount &lt;= 80000 报 {@code EXCEED_LIMIT}；
 * 余额不足报 {@code INSUFFICIENT_BALANCE}；金额大于 80000 故意 sleep 触发超时；
 * 转入账户不存在报 {@code ACCOUNT_NOT_FOUND}。</p>
 */
public final class TransferTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 交易流水号：ToolHandler 拿不到 call id，用原子序号生成 6 位交易号。 */
    private static final AtomicLong TXN_SEQ = new AtomicLong();

    /** 租户:账号 → 余额。内存模拟账户，生产应替换为真正的账户服务。 */
    private static final Map<String, Double> ACCOUNTS = new ConcurrentHashMap<>();

    static {
        ACCOUNTS.put(accountKey("tenant_a", "ACC-A-123456"), 100000.0);
        ACCOUNTS.put(accountKey("tenant_a", "ACC-A-654321"), 5000.0);
        ACCOUNTS.put(accountKey("tenant_a", "ACC-A-888888"), 20000.0);
        ACCOUNTS.put(accountKey("tenant_b", "ACC-B-111111"), 50000.0);
    }

    private TransferTool() {
    }

    /**
     * 构建转账工具定义：写操作 + 高风险 + 强制审批，超时 2 秒（小于 handler 的 3 秒 sleep）。
     */
    public static ToolDefinition definition() {
        return new ToolDefinition(
                "transfer",
                "1.0.0",
                "在租户内两个账户之间转账",
                inputSchema(),
                outputSchema(),
                TransferTool::handle,
                new ToolPolicy(Effect.WRITE, RiskLevel.HIGH, "transfer:create",
                        false, false, true, 2.0, 0, false, true),
                Set.of(),
                TransferTool::precheck,
                TransferTool::canonicalTarget);
    }

    private static JsonNode inputSchema() {
        return schema("""
                {"type":"object",
                 "properties":{
                   "from_account":{"type":"string","pattern":"^ACC-[A-Z]-[0-9]{6}$"},
                   "to_account":{"type":"string","pattern":"^ACC-[A-Z]-[0-9]{6}$"},
                   "amount":{"type":"number","exclusiveMinimum":0,"maximum":100000}},
                 "required":["from_account","to_account","amount"],
                 "additionalProperties":false}
                """);
    }

    private static JsonNode outputSchema() {
        return schema("""
                {"type":"object",
                 "properties":{
                   "txn_id":{"type":"string"},
                   "from":{"type":"string"},
                   "to":{"type":"string"},
                   "amount":{"type":"number"},
                   "status":{"type":"string"}},
                 "required":["txn_id","from","to","amount","status"],
                 "additionalProperties":false}
                """);
    }

    /**
     * 业务预检：只判断、不改余额。先拦金额区间，再查余额充足。
     */
    private static void precheck(JsonNode args, ExecutionContext ctx) {
        double amount = args.path("amount").asDouble();
        if (amount > 50000 && amount <= 80000) {
            throw new PolicyDenied("EXCEED_LIMIT", "教学金额区间拦截：50000 < amount <= 80000");
        }
        double balance = balanceOf(ctx.tenantId(), args.path("from_account").asText());
        if (balance < amount) {
            throw new PolicyDenied("INSUFFICIENT_BALANCE", "转出账户余额不足");
        }
    }

    /**
     * 转账执行：先做超时模拟，再扣款入账。sleep 必须在扣款前，保证超时无幽灵副作用。
     */
    private static JsonNode handle(JsonNode args, ExecutionContext ctx) throws Exception {
        double amount = args.path("amount").asDouble();
        if (amount > 80000) {
            Thread.sleep(3000);
        }
        String from = args.path("from_account").asText();
        String to = args.path("to_account").asText();
        String fromKey = accountKey(ctx.tenantId(), from);
        String toKey = accountKey(ctx.tenantId(), to);
        if (!ACCOUNTS.containsKey(toKey)) {
            throw new PolicyDenied("ACCOUNT_NOT_FOUND", "转入账户不存在");
        }
        ACCOUNTS.computeIfPresent(fromKey, (k, v) -> v - amount);
        ACCOUNTS.computeIfPresent(toKey, (k, v) -> v + amount);

        ObjectNode out = MAPPER.createObjectNode();
        out.put("txn_id", String.format("%06d", TXN_SEQ.incrementAndGet()));
        out.put("from", from);
        out.put("to", to);
        out.put("amount", amount);
        out.put("status", "SUCCESS");
        return out;
    }

    /**
     * 写锁目标：同一租户的同一转出账户串行，防止并发扣款竞争。
     */
    private static String canonicalTarget(JsonNode args, ExecutionContext ctx) {
        return ctx.tenantId() + ":" + args.path("from_account").asText();
    }

    /**
     * 查询账户余额：不存在的账户余额视为 0，测试与预检共用。
     */
    public static double balanceOf(String tenantId, String accountId) {
        return ACCOUNTS.getOrDefault(accountKey(tenantId, accountId), 0.0);
    }

    /**
     * 测试隔离：把内存账户重置到初始余额，避免用例之间互相污染。
     */
    static void resetAccounts() {
        ACCOUNTS.clear();
        ACCOUNTS.put(accountKey("tenant_a", "ACC-A-123456"), 100000.0);
        ACCOUNTS.put(accountKey("tenant_a", "ACC-A-654321"), 5000.0);
        ACCOUNTS.put(accountKey("tenant_a", "ACC-A-888888"), 20000.0);
        ACCOUNTS.put(accountKey("tenant_b", "ACC-B-111111"), 50000.0);
    }

    private static String accountKey(String tenantId, String accountId) {
        return tenantId + ":" + accountId;
    }

    private static JsonNode schema(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("invalid embedded schema", e);
        }
    }
}
