package com.nasa.runtime.core.base;

import java.io.Serializable;

/**
 * Nasa
 * 环形数据结构基础接口
 * @param <ResetParam> 重置参数泛型
 */
public interface Ring<ResetParam, OnReplace, OnRemove, OnClear> extends Serializable {

    /**
     * 环大小
     */
    int ringCapacity();

    /**
     * 环重置
     */
    void ringReset(ResetParam resetParam);

    // ==================== 操作事件 ====================

    /**
     * 替换事件
     */
    void onReplace(OnReplace listener);

    /**
     * 删除事件
     */
    void onRemove(OnRemove listener);

    /**
     * 清空事件
     */
    void onClear(OnClear listener);

    /**
     * 清除所有监听事件
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
