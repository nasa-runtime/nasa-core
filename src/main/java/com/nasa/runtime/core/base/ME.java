package com.nasa.runtime.core.base;

import com.nasa.runtime.core.exception.ReflectException;
import com.nasa.runtime.core.utils.ContextUtils;
import com.nasa.runtime.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 本机相关操作
 */
@Slf4j
public class ME {

    private ME() {}

    public static final String SEQUENCE = "sequence";

    static Function<String, String> MAPPER = filename -> {
        // 在日志文件目录创建sequence文件存储当前机器16位唯一标识
        String dir = ContextUtils.getPropertySafe("logging.file.path", null);
        if (Objects.isNull(dir)) {
            String appName = ContextUtils.getPropertySafe("nasa.application.name", "nasa-runtime");
            dir = System.getProperty("nasa.sequence.dir", "logs/" + appName);
        }
        try {
            Path dirPath = Path.of(dir);
            Path filePath = dirPath.resolve(filename);
            String rs = Files.exists(filePath) ? Files.readString(filePath, StandardCharsets.UTF_8) : null;
            if (StringUtils.isBlank(rs)) {
                Files.createDirectories(dirPath);
                rs = StringUtils.random(16);
                Files.writeString(filePath, rs, StandardCharsets.UTF_8);
            }
            return rs;
        } catch (Exception e) {
            throw new ReflectException(e.getMessage(), e);
        }
    };
    /* 当前服务的sequence */
    static ConcurrentMap<String, String> SEQ_MAP = new ConcurrentHashMap<>();

    /**
     * 获取本地序列号，长时间有效，不受服务重启影响
     * @param filename 落盘文件名
     */
    public static String sequence(String filename) {
        return SEQ_MAP.computeIfAbsent(filename, MAPPER);
    }

    /**
     * 获取本地序列号，长时间有效，不受服务重启影响
     */
    public static String sequence() {
        return sequence(SEQUENCE);
    }

    /**
     * 判断是否为我
     */
    public static boolean isMe(String filename, String sequence) {
        return sequence(filename).equals(sequence);
    }

    /**
     * 判断是否为我
     */
    public static boolean isMe(String sequence) {
        return isMe(SEQUENCE, sequence);
    }

    /* 缓存本机ip */
    private static String LOCAL_IP;

    /**
     * 获取本地ip
     */
    public static String localIp() {
        if (Objects.nonNull(LOCAL_IP)) {
            return LOCAL_IP;
        }
        // 如果启动参数指定了ip
        String ip = System.getProperty("local.network.ip");
        if (StringUtils.isNotBlank(ip)) {
            return LOCAL_IP = ip;
        }
        // 如果启动参数指定了网卡名称，以指定为准
        String networkName = System.getProperty("local.network.name");
        if (Objects.nonNull(networkName)) {
            ip = localIp(networkName);
            return LOCAL_IP = Objects.isNull(ip) ? "127.0.0.1" : ip;
        }

        // 没有指定，则顺序读取
        networkName = "eth0";
        ip = localIp(networkName);
        if (Objects.isNull(ip)) {
            ip = localIp("ens32");
        }
        return LOCAL_IP = Objects.isNull(ip) ? "127.0.0.1" : ip;
    }

    /**
     * 获取本地ip
     * @param networkName 网卡名称，如：eth0、ens32等
     */
    public static String localIp(String networkName) {
        try {
            Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
            while (enumeration.hasMoreElements()) {
                NetworkInterface network = enumeration.nextElement();
                if (!network.getName().equals(networkName)) {
                    continue;
                }
                Enumeration<InetAddress> inets = network.getInetAddresses();
                while (inets.hasMoreElements()) {
                    InetAddress inet = inets.nextElement();
                    if (inet instanceof Inet4Address) {
                        return inet.getHostAddress();
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.error("获取本地ip地址出错：{}", e.getMessage(), e);
            throw new ReflectException(e.getMessage(), e);
        }
    }

}
