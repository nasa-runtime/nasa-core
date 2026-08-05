package com.nasa.runtime.core.utils;

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

    public static Logger getLogger(Class<?> clazz) {
        return logMap.computeIfAbsent(clazz, LoggerFactory::getLogger);
    }

}
