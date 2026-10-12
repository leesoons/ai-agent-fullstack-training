package com.leesoons.agentloop;

import com.leesoons.toolruntime.AgentLoop;
import com.leesoons.toolruntime.ExecutionContext;
import com.leesoons.toolruntime.LoopMessage;
import com.leesoons.toolruntime.ModelClient;
import com.leesoons.toolruntime.ToolSnapshot;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * Agent 对外 HTTP 入口：接收用户问题，跑完整 Agent Loop，返回最终回答。
 */
@RestController
public class AgentController {

    private final AgentLoop loop;
    private final ModelClient modelClient;
    private final ToolSnapshot snapshot;

    public AgentController(AgentLoop loop, ModelClient modelClient, ToolSnapshot snapshot) {
        this.loop = loop;
        this.modelClient = modelClient;
        this.snapshot = snapshot;
    }

    @PostMapping("/v1/agent")
    public Mono<AgentResponse> ask(@RequestBody AgentRequest request) {
        // 演示环境固定身份与权限；生产环境应从鉴权上下文注入。
        ExecutionContext context = new ExecutionContext(
                "http", "demo-user", "demo-tenant",
                Set.of("weather:read", "database:read"));
        List<LoopMessage> initial = List.of(LoopMessage.of("user", request.message()));
        String answer = loop.run(initial, snapshot, context, modelClient);
        return Mono.just(new AgentResponse(answer));
    }
}
