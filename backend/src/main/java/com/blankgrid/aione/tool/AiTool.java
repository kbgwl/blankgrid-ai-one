package com.blankgrid.aione.tool;

/**
 * 一个 AI 员工可以主动调用的工具。
 *
 * <p>设计取舍:返回 String 而不是结构化对象,是因为工具结果最终要拼进对话消息给模型读,
 * 中间再转一层 JSON 反而多一次序列化、还容易把 token 浪费在括号和引号上。
 * 工具自己负责把结果压成"模型一读就懂"的紧凑文本。
 *
 * <p>约定:{@link #execute} 可以抛异常,但 {@link ToolRegistry#invoke} 会兜住并转成一句
 * 人类可读的失败说明返回给模型 —— 让模型自己决定换个关键词重试,而不是整条任务挂掉。
 */
public interface AiTool {

    /** 工具名,给模型看。用蛇形小写,和 OpenAI function 命名规范一致。 */
    String name();

    /** 什么时候该用这个工具、参数怎么填。模型靠这段文字决定要不要调,写清楚比写漂亮重要。 */
    String description();

    /** 参数的 JSON Schema 字符串(OpenAI function.parameters 的格式)。 */
    String paramsSchema();

    /**
     * 执行。返回给模型看的文本结果。
     *
     * @param argsJson 模型给的参数,已是 JSON 字符串;可能为空(无参工具)
     * @param ctx      调用上下文(哪个目标、哪个任务、谁在调),用于审计与配额
     */
    String execute(String argsJson, ToolContext ctx) throws Exception;

    /**
     * 是否当前可用。比如电商工具在没配 AppKey 时应该返回 false,
     * 这样它压根不会出现在模型的工具清单里 —— 比让模型调一次然后失败要省钱得多。
     */
    default boolean available() {
        return true;
    }

    /** 不可用时给用户看的原因,用于设置页/日志排查。 */
    default String unavailableReason() {
        return "";
    }

    /** 归类,便于前端分组展示与按岗位授权。 */
    default String category() {
        return "general";
    }
}
