package com.leesoons.llmgateway.service;

import com.leesoons.llmgateway.model.PromptCreate;
import com.leesoons.llmgateway.model.PromptRecord;

import java.util.List;

/**
 * Prompt 模板存储抽象。当前提供进程内实现，后续可替换为数据库。
 */
public interface PromptStore {

    PromptRecord createVersion(PromptCreate create);

    PromptRecord get(String id, Integer version);

    List<PromptRecord> list();
}
