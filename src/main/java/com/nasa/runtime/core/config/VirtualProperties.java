package com.nasa.runtime.core.config;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class VirtualProperties {
    /* 是否开启虚拟线程 */
    private boolean enable = false;
    /* 虚拟线程池名称 */
    private String virtualName;
    /* 虚拟线程名称下标开始值 */
    private Long virtualStart;
}
