package com.leesoons.llmgateway.service;

import com.leesoons.llmgateway.core.GatewayErrorCode;
import com.leesoons.llmgateway.core.GatewayException;
import com.leesoons.llmgateway.model.PromptCreate;
import com.leesoons.llmgateway.model.PromptRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程内 Prompt 版本存储。同一 id 支持多版本，激活最新版本。
 */
@Component
public class InMemoryPromptStore implements PromptStore {

    private final Map<String, Map<Integer, PromptRecord>> byId = new LinkedHashMap<>();

    @Override
    public synchronized PromptRecord createVersion(PromptCreate create) {
        Map<Integer, PromptRecord> versions = byId.computeIfAbsent(create.getId(), key -> new LinkedHashMap<>());
        int version = versions.keySet().stream().max(Integer::compareTo).orElse(0) + 1;

        if (create.isActivate()) {
            versions.values().forEach(record -> record.setActive(false));
        }

        PromptRecord record = new PromptRecord();
        record.setId(create.getId());
        record.setVersion(version);
        record.setName(create.getName());
        record.setDescription(create.getDescription());
        record.setRole(create.getRole());
        record.setContent(create.getContent());
        record.setActive(create.isActivate());
        record.setCreatedAt(Instant.now().toString());
        versions.put(version, record);
        return record;
    }

    @Override
    public synchronized PromptRecord get(String id, Integer version) {
        Map<Integer, PromptRecord> versions = byId.get(id);
        if (versions == null) {
            throw notFound(id, version);
        }
        if (version != null) {
            PromptRecord record = versions.get(version);
            if (record == null) {
                throw notFound(id, version);
            }
            return record;
        }
        return versions.values().stream()
                .filter(PromptRecord::isActive)
                .max(Comparator.comparingInt(PromptRecord::getVersion))
                .orElseThrow(() -> notFound(id, null));
    }

    @Override
    public synchronized List<PromptRecord> list() {
        List<PromptRecord> result = new ArrayList<>();
        byId.values().forEach(versions -> versions.values().forEach(result::add));
        result.sort(Comparator.comparing(PromptRecord::getId)
                .thenComparing(Comparator.comparingInt(PromptRecord::getVersion).reversed()));
        return result;
    }

    private GatewayException notFound(String id, Integer version) {
        String target = version == null ? "active" : "version " + version;
        return new GatewayException("Prompt '" + id + "' " + target + " was not found",
                HttpStatus.NOT_FOUND, "invalid_request_error", GatewayErrorCode.PROMPT_NOT_FOUND, "prompt");
    }
}
