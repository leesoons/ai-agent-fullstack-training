package com.leesoons.toolruntime;

/**
 * 命名投影：把「内部事实名」投影成模型可调用的「合法工具名」。
 *
 * <p>模型侧 function name 受 {@code ^[A-Za-z0-9_-]{1,64}$} 约束，而内部名可以
 * 携带命名空间点号（如 {@code order.get_status}）。投影后：点号变为 {@code __}，
 * 其余非法字符退化为 {@code _}。投影不是单射，因此 {@link ToolRegistry#register}
 * 必须对投影名做显式碰撞检查。</p>
 */
public final class NameProjection {

    private static final String MODEL_TOOL_NAME = "^[A-Za-z0-9_-]{1,64}$";

    private NameProjection() {
    }

    /**
     * 将内部名投影为模型可见的稳定名称；投影结果非法时直接拒绝。
     */
    public static String toModelToolName(String internalName) {
        String mapped = internalName.replace(".", "__").replaceAll("[^A-Za-z0-9_-]", "_");
        if (!mapped.matches(MODEL_TOOL_NAME)) {
            throw new IllegalArgumentException("Tool name cannot be projected safely: " + internalName);
        }
        return mapped;
    }
}
