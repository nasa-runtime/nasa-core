package com.nasa.runtime.core.utils;

import com.nasa.runtime.core.base.ME;
import com.nasa.runtime.core.cache.SimpleCache;
import com.nasa.runtime.core.exception.ReflectException;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.function.Function;

/**
 * Nasa
 * ip 相关工具类
 */
@SuppressWarnings("unused")
@Slf4j
public abstract class IpUtils {
    
    public static final String Scenes = IpUtils.class.getName();

    /**
     * 添加ip区域远程执行器
     */
    public static void addExecute(Function<String, String> execute) {
        invokeIpArea("addExecute", ColUtils.toArray(Function.class), execute);
    }

    /**
     * 添加ip区域远程执行器
     */
    public static void addExecute(Integer index, Function<String, String> execute) {
        invokeIpArea("addExecute", ColUtils.toArray(Integer.class, Function.class), index, execute);
    }

    /**
     * 移除ip区域远程执行器
     */
    public static void removeExecute(Class<? extends Function<String, String>> clazz) {
        invokeIpArea("removeExecute", ColUtils.toArray(Class.class), clazz);
    }

    public static SimpleCache<String, String> getSimpleCache() {
        return invokeIpArea("getSimpleCache", new Class<?>[0]);
    }

    public static void setSimpleCache(SimpleCache<String, String> simpleCache) {
        invokeIpArea("setSimpleCache", ColUtils.toArray(SimpleCache.class), simpleCache);
    }

    /**
     * 获取本地所有网卡IP，排除回文地址、虚拟地址
     */
    public static Set<String> localIpSet() {
        Set<String> set = new HashSet<>();
        // 如果启动参数指定了网卡名称，以指定为准
        String networkName = System.getProperty("local.network.name");
        if (Objects.nonNull(networkName)) {
            set.add(ME.localIp(networkName));
            return set;
        }
        try {
            Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
            while (enumeration.hasMoreElements()) {
                NetworkInterface network = enumeration.nextElement();
                if (network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> inets = network.getInetAddresses();
                while (inets.hasMoreElements()) {
                    InetAddress inet = inets.nextElement();
                    if (inet.isLoopbackAddress() || !inet.isSiteLocalAddress() || inet.isAnyLocalAddress()) {
                        continue;
                    }
                    set.add(inet.getHostAddress());
                }
            }
            return set;
        } catch (Exception e) {
            log.error("获取本地ip地址出错：{}", e.getMessage(), e);
            throw new ReflectException(e.getMessage(), e);
        }
    }

    /**
     * 获取本地ip
     */
    public static String localIp() {
        return ME.localIp();
    }

    /**
     * 获取客户端ip
     */
    public static String getIp(String ip) {
        if ("0:0:0:0:0:0:0:1".equals(ip)) {
            ip = "127.0.0.1";
        }
        if (ip.contains(StringUtils.Mark_comma)) {
            ip = ip.substring(0, ip.indexOf(StringUtils.Mark_comma));
        }
        return ip;
    }

    /**
     * 根据ip获取区域
     */
    public static String getArea(String ip) {
        String area = invokeIpArea("getArea", ColUtils.toArray(String.class), ip);
        return StringUtils.isBlank(area) ? ip : area;
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeIpArea(String method, Class<?>[] parameterTypes, Object... args) {
        try {
            Class<?> clazz = ReflectUtils.forName("com.nasa.runtime.web.utils.IpAreaUtils");
            return (T) ReflectUtils.invokeStatic(clazz, method, parameterTypes, args);
        } catch (Throwable ignore) {
            return null;
        }
    }

}
