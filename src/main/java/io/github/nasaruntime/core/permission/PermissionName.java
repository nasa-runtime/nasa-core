package io.github.nasaruntime.core.permission;

/**
 * Nasa
 * 权限名称接口
 */
public interface PermissionName {

    /**
     * 业务作用：返回权限的稳定标识名，是权限比对与持久化的唯一依据。
     * 枚举实现天然满足该契约；返回值一经发布不应修改，否则已授权数据会失配。
     *
     * 参数说明: 无。
     * 返回: 权限名称，不允许为 null。
     */
    String name();

}
