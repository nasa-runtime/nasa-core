package io.github.nasaruntime.core.base;

import java.io.Serializable;

/**
 * Nasa
 * 环形数据结构基础接口
 */
public interface Ring<ResetParam, OnReplace, OnRemove, OnClear> extends Serializable {

    /**
     * 业务作用：报告环形容量上限，超出后最旧的元素会被覆盖。
     *
     * 参数说明: 无。
     * 返回: 容量上限。
     */
    int ringCapacity();

    /**
     * 业务作用：环重置
     *
     * @param resetParam 见上述说明
     * 返回: 无返回值。
     */
    void ringReset(ResetParam resetParam);

    // ==================== 操作事件 ====================

    /**
     * 业务作用：替换事件
     *
     * @param listener 见上述说明
     * 返回: 无返回值。
     */
    void onReplace(OnReplace listener);

    /**
     * 业务作用：删除事件
     *
     * @param listener 见上述说明
     * 返回: 无返回值。
     */
    void onRemove(OnRemove listener);

    /**
     * 业务作用：清空事件
     *
     * @param listener 见上述说明
     * 返回: 无返回值。
     */
    void onClear(OnClear listener);

    /**
     * 业务作用：清除所有监听事件
     *
     * 参数说明: 无。
     * 返回: 无返回值。
     */
    void clearListeners();

    /**
     * 环数据变更事件
     */
    enum Event {
        REPLACE,
        REMOVE,
        CLEAR
    }
}
