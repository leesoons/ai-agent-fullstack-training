package com.leesoons.toolruntime;

/**
 * 权限执行模式：只能由服务端注入，模型不能通过工具参数切换。
 *
 * <ul>
 *   <li>{@code DEFAULT}：普通交互，低风险动作可返回 confirm</li>
 *   <li>{@code PLAN}：仅分析和读取，始终拒绝写与 Shell</li>
 *   <li>{@code BYPASS_PERMISSIONS}：跳过普通低风险确认，但不越过强制审批</li>
 *   <li>{@code DONT_ASK}：无法交互时，把需要确认的动作直接拒绝</li>
 * </ul>
 */
public enum PermissionMode {
    DEFAULT,
    PLAN,
    BYPASS_PERMISSIONS,
    DONT_ASK
}
