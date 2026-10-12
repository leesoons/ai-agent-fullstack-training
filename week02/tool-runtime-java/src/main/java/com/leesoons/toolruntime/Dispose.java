package com.leesoons.toolruntime;

/**
 * 可撤销操作的句柄：谁注册，谁负责调用 dispose 清理。
 */
@FunctionalInterface
public interface Dispose {

    void dispose();
}
