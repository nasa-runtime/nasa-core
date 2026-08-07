package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.exception.ReflectException;
import io.github.nasaruntime.core.utils.ContextUtils;
import io.github.nasaruntime.core.utils.StringUtils;
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

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
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
     * 业务作用：取得本节点的序号，用于在集群中区分实例并参与唯一标识生成。
     *
     * @param filename 见上述说明
     * 返回: 本节点序号。
     */
    public static String sequence(String filename) {
        return SEQ_MAP.computeIfAbsent(filename, MAPPER);
    }

    /**
     * 业务作用：取得本节点的序号，用于在集群中区分实例并参与唯一标识生成。
     *
     * 参数说明: 无。
     * 返回: 本节点序号。
     */
    public static String sequence() {
        return sequence(SEQUENCE);
    }

    /**
     * 业务作用：判断是否为我
     *
     * @param filename 见上述说明
     * @param sequence 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isMe(String filename, String sequence) {
        return sequence(filename).equals(sequence);
    }

    /**
     * 业务作用：判断是否为我
     *
     * @param sequence 见上述说明
     * 返回: 满足上述判定条件时返回 true，否则返回 false。
     */
    public static boolean isMe(String sequence) {
        return isMe(SEQUENCE, sequence);
    }

    /* 缓存本机ip */
    private static String LOCAL_IP;

    /**
     * 业务作用：取得本机 IP，用于节点标识与本地网络通信。
     *
     * 参数说明: 无。
     * 返回: 本机 IP。
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
     * 业务作用：取得本机 IP，用于节点标识与本地网络通信。
     *
     * @param networkName 见上述说明
     * 返回: 本机 IP。
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
