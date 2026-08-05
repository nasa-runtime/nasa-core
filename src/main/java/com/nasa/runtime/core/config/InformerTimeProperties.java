package com.nasa.runtime.core.config;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * Nasa
 * HTTP超时、异常通知参数配置
 */
@Setter
@Getter
public class InformerTimeProperties {

    /**
     * HTTP 调用 Informer配置
     * key 如果带特殊字符，如：/telegram-bot/task/notify
     * 在yml中需要配置为："[/telegram-bot/task/notify]": 60000
     */
    private final Map<String, InformerProperties> temp = new HashMap<>();

    /**
     * InterfaceTimeInterceptor Informer配置
     */
    private final InformerProperties interFc = new InformerProperties();

    /**
     * MybatisPlusSQLTimeInterceptor Informer配置
     */
    private final InformerProperties sql = new InformerProperties();

    @Setter
    @Getter
    public static class InformerProperties {
        /* 是否启用超时异常通知 */
        private boolean enable = true;
        /* 接口响应通知时间，超过这个毫秒数，就会调用通知接口 */
        private long informTime = 3000L;
        /* 仅留下指定堆栈前缀的信息 */
        private String stackPrefix;
        /* 接口响应通知时间， (url, time)，优先级高于informTime */
        private final Map<String, Long> others = new HashMap<>();
    }
}
