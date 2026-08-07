package io.github.nasaruntime.core.enums;

import io.github.nasaruntime.core.base.SerialEnum;

/**
 * Nasa
 */
@SuppressWarnings("unused")
public enum Sort implements SerialEnum {

    ASC, DESC;

    /**
     * 业务作用：按序号还原排序方向，用于从紧凑的数字表示恢复枚举。
     *
     * @param ordinal 序号，0 表示升序
     * 返回: 序号为 0 返回 ASC，其余一律返回 DESC。
     */
    public static Sort of(int ordinal) {
        return ordinal == 0 ? ASC : DESC;
    }

    /**
     * 业务作用：按文本还原排序方向，容忍大小写差异，供解析查询参数使用。
     *
     * @param sort 排序方向文本
     * 返回: 忽略大小写等于 "asc" 时返回 ASC，其余（含 null）一律返回 DESC。
     */
    public static Sort of(String sort) {
        return "asc".equalsIgnoreCase(sort) ? ASC : DESC;
    }

    /**
     * 业务作用：判断是否为升序，避免调用方直接比较枚举常量。
     *
     * 参数说明: 无。
     * 返回: 当前为 ASC 返回 true。
     */
    public boolean isAsc() {
        return this == ASC;
    }

    /**
     * 业务作用：判断是否为降序，避免调用方直接比较枚举常量。
     *
     * 参数说明: 无。
     * 返回: 当前为 DESC 返回 true。
     */
    public boolean isDesc() {
        return this == DESC;
    }

    /**
     * 业务作用：按 SerialEnum 的序列化契约还原排序方向，供协议编解码使用。
     * 与 of(int) 的区别是走统一的序列化入口，可处理 null 与越界值。
     *
     * @param o 序列化序号，可为 null
     * 返回: 对应的枚举常量；无法匹配时由 SerialEnum 决定结果。
     */
    public static Sort of(Integer o) {
        return SerialEnum.of(Sort.class, o);
    }
}
