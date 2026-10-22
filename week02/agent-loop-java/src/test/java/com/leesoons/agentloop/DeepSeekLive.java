package com.leesoons.agentloop;

import com.leesoons.agentcore.AgentLoop;
import com.leesoons.agentcore.LoopMessage;
import com.leesoons.agentcore.ModelClient;
import com.leesoons.toolruntime.ExecutionContext;
import com.leesoons.toolruntime.ToolSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 2-5 Live 验收：真实调用 DeepSeek 跑完整 Agent Loop。
 *
 * <p>缺 {@code DEEPSEEK_V4_PRO_API_KEY} 时必须失败，绝不允许回退到假模型。
 * 默认不随 {@code mvn test} 运行，显式执行：{@code mvn -pl week02/agent-loop-java -Dtest=DeepSeekLive test}。</p>
 */
@SpringBootTest
class DeepSeekLive {

    @Autowired
    private AgentLoop loop;

    @Autowired
    private ModelClient modelClient;

    @Autowired
    private ToolSnapshot snapshot;

    @Test
    void liveLoopUsesRealDeepSeek() {
        String key = System.getenv("DEEPSEEK_V4_PRO_API_KEY");
        if (key == null || key.isBlank()) {
            fail("缺少 DEEPSEEK_V4_PRO_API_KEY，live 验收必须提供真实密钥");
        }

        ExecutionContext context = new ExecutionContext(
                "live", "trainer", "tenant", Set.of("weather:read", "database:read"));
        String answer = loop.run(
                List.of(LoopMessage.of("user", "查询北京天气")), snapshot, context, modelClient);

        assertNotNull(answer);
        assertFalse(answer.isBlank());
    }
}
