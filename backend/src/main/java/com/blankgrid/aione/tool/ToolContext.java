package com.blankgrid.aione.tool;

/**
 * 一次工具调用的上下文。
 *
 * <p>带上 goalId / taskId 不是为了方便,是为了出事时能查:哪个目标、哪条交付物、
 * 哪个岗位调用了什么外部接口、花了多少配额。没有这个,线上出问题只能靠猜。
 */
public class ToolContext {

    public final Long goalId;
    public final Long taskId;
    public final String employeeName;

    public ToolContext(Long goalId, Long taskId, String employeeName) {
        this.goalId = goalId;
        this.taskId = taskId;
        this.employeeName = employeeName == null ? "" : employeeName;
    }

    public static ToolContext of(Long goalId, Long taskId) {
        return new ToolContext(goalId, taskId, "");
    }

    public static ToolContext empty() {
        return new ToolContext(null, null, "");
    }
}
