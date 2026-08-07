package io.github.nasaruntime.core.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nasa
 * 获取一个SLF的log
 */
@SuppressWarnings("unused")
public abstract class LogUtils {

    private static final Map<Class<?>, Logger> logMap = new ConcurrentHashMap<>();

    /**
     * 业务作用：取得指定类的日志器。
     *
     * @param clazz 目标类型
     * 返回: 该类的日志器实例。
     */
    public static Logger getLogger(Class<?> clazz) {
        return logMap.computeIfAbsent(clazz, LoggerFactory::getLogger);
    }

}
