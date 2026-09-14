package com.leesoons.llmgateway.run;

/**
 * 一次 Run 内部缓冲的一条事件。seq 单调递增，是断点续传的游标。
 */
public record RunEvent(int seq, String type, Object data) {
}
