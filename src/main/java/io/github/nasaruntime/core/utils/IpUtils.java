package io.github.nasaruntime.core.utils;

import io.github.nasaruntime.core.base.ME;
import io.github.nasaruntime.core.cache.SimpleCache;
import io.github.nasaruntime.core.exception.ReflectException;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
     * 业务作用：向可选的 IP 归属地组件追加远程查询执行器；组件未安装时保持静默降级。
     *
     * @param execute 根据 IP 返回归属地文本的执行器
     * 返回: 无返回值；可选组件缺失或调用失败时不改变核心库状态。
     */
    public static void addExecute(Function<String, String> execute) {
        invokeIpArea("addExecute", FUNCTION_PARAMETER_TYPES, execute);
    }

    /**
     * 业务作用：按指定顺序向可选的 IP 归属地组件插入远程查询执行器；组件未安装时保持静默降级。
     *
     * @param index 执行器插入位置
     * @param execute 根据 IP 返回归属地文本的执行器
     * 返回: 无返回值；可选组件缺失或调用失败时不改变核心库状态。
     */
    public static void addExecute(Integer index, Function<String, String> execute) {
        invokeIpArea("addExecute", INDEXED_FUNCTION_PARAMETER_TYPES, index, execute);
    }

    /**
     * 业务作用：按执行器实现类型从可选的 IP 归属地组件移除远程查询策略。
     *
     * @param clazz 需要移除的执行器实现类型
     * 返回: 无返回值；可选组件缺失或调用失败时不改变核心库状态。
     */
    public static void removeExecute(Class<? extends Function<String, String>> clazz) {
        invokeIpArea("removeExecute", CLASS_PARAMETER_TYPES, clazz);
    }

    /**
     * 业务作用：读取可选 IP 归属地组件当前使用的查询缓存，供集成层诊断或替换缓存策略。
     *
     * 参数说明: 无。
     * 返回: 可选组件的缓存实例；组件缺失或调用失败时返回 null。
     */
    public static SimpleCache<String, String> getSimpleCache() {
        return invokeIpArea("getSimpleCache", EMPTY_PARAMETER_TYPES);
    }

    /**
     * 业务作用：替换可选 IP 归属地组件的查询缓存，使集成层可以接入自定义缓存实现。
     *
     * @param simpleCache 新缓存实现
     * 返回: 无返回值；可选组件缺失或调用失败时不改变核心库状态。
     */
    public static void setSimpleCache(SimpleCache<String, String> simpleCache) {
        invokeIpArea("setSimpleCache", CACHE_PARAMETER_TYPES, simpleCache);
    }

    /**
     * 业务作用：收集本机所有可用于站点内通信的网卡地址，排除回环、虚拟、任意地址和非站点本地地址。
     *
     * 参数说明: 无。
     * 返回: 去重后的本地 IP 集合；枚举网卡失败时记录错误并抛出 ReflectException。
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
     * 业务作用：取得框架选定的本机主 IP，供节点标识和本地网络通信使用。
     *
     * 参数说明: 无。
     * 返回: ME 根据当前网络配置解析出的本机 IP。
     */
    public static String localIp() {
        return ME.localIp();
    }

    /**
     * 业务作用：规范化代理链或 IPv6 回环形式的客户端地址，提取业务应使用的首个 IP。
     *
     * @param ip 原始客户端地址或逗号分隔的代理链
     * 返回: 规范化后的首个客户端 IP；入参为 IPv6 回环时返回 127.0.0.1。
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
     * 业务作用：通过可选 IP 归属地组件查询地址区域；核心库独立使用时安全退化为原 IP。
     * 实现按系统属性 {@code nasa.ip-area.class} 优先、其次内置候选的顺序解析一次；
     * 系统属性必须在首次调用本类任一归属地 API 前设置，运行期再改不会替换已选定的实现。
     *
     * @param ip 待查询的 IP 地址
     * 返回: 非空归属地文本；组件缺失、查询失败或结果为空时返回原 IP。
     */
    public static String getArea(String ip) {
        String area = invokeIpArea("getArea", STRING_PARAMETER_TYPES, ip);
        return StringUtils.isBlank(area) ? ip : area;
    }

    /**
     * IP 归属地实现类的候选全限定名，按序探测。
     * 使用其它实现时通过系统属性 {@code nasa.ip-area.class} 指定，指定后优先于内置候选。
     */
    private static final String[] IP_AREA_CANDIDATES = {
            "io.github.nasaruntime.web.utils.IpAreaUtils",
    };

    /** 反射签名数组在类初始化时只创建一次，避免可选组件缺失时每次 API 调用仍产生短命数组。 */
    private static final Class<?>[] EMPTY_PARAMETER_TYPES = {};
    private static final Class<?>[] FUNCTION_PARAMETER_TYPES = {Function.class};
    private static final Class<?>[] INDEXED_FUNCTION_PARAMETER_TYPES = {Integer.class, Function.class};
    private static final Class<?>[] CLASS_PARAMETER_TYPES = {Class.class};
    private static final Class<?>[] CACHE_PARAMETER_TYPES = {SimpleCache.class};
    private static final Class<?>[] STRING_PARAMETER_TYPES = {String.class};

    /**
     * 延迟初始化持有者由 JVM 保证至多初始化一次，并安全发布解析结果。
     * 不能用无锁 volatile check-then-act 冒充“一次解析”，否则首批并发请求仍会重复扫描类路径。
     */
    private static final class IpAreaClassHolder {
        private static final Optional<Class<?>> VALUE = resolveIpAreaClassSafely();

        /**
         * 业务作用：阻止一次性解析持有者被业务代码实例化，确保解析只受 JVM 类初始化驱动。
         *
         * 参数说明: 无。
         * 返回: 不产生实例；构造器仅用于封闭工具类入口。
         */
        private IpAreaClassHolder() {
        }
    }

    /**
     * 业务作用：读取 JVM 类初始化机制安全发布的 IP 归属地实现解析结果。
     * <p>
     * 必须缓存而不是每次调用都探测：{@link ReflectUtils#forName(String)} 在类缺失时抛
     * {@code ReflectException}，而该异常在构造时会把整个调用栈打进 error 日志。
     * 独立使用 nasa-core（不带 web 模块）时归属地实现本就不存在，逐次探测会让每一次
     * {@link #getArea(String)} 都构造一次异常并刷一条堆栈日志，在请求路径上足以压垮日志管道。
     * 这里改用 {@code isPresent=true} 的重载，缺失时返回 null 而不抛异常，再把“确认不存在”
     * 这个结论本身缓存下来。后续缺失路径只有静态结果读取和分支判断，不再分配形参数组或 varargs 数组。
     *
     * 参数说明: 无。
     * 返回: 命中的实现类；全部候选都不存在时返回 {@link Optional#empty()}，且该结论在进程期内缓存。
     */
    private static Optional<Class<?>> ipAreaClass() {
        return IpAreaClassHolder.VALUE;
    }

    /**
     * 业务作用：为一次性解析加上失败兜底，保证降级 API 在任何情况下都不会退化为硬失败。
     * <p>
     * 持有者的静态初始化器一旦抛出，类初始化即告失败，此后每一次 {@link #getArea(String)}
     * 都会抛 {@code NoClassDefFoundError} 且不可恢复——一个契约上"组件缺失即静默降级"的 API
     * 反而变成永久不可用。解析过程中读系统属性、加载类都可能抛出非 {@code LinkageError} 的错误，
     * 因此这里必须把整个解析包起来，把任何异常收敛成"没有可选实现"这一安全结论。
     *
     * 参数说明: 无。
     * 返回: 解析结果；解析过程本身失败时返回 {@link Optional#empty()} 并记录一次告警，
     *      绝不向调用方传播异常。
     */
    private static Optional<Class<?>> resolveIpAreaClassSafely() {
        try {
            return resolveIpAreaClass();
        } catch (Throwable failure) {
            // 这里是类初始化器的最外层，向上抛出会永久毒化本类；只能降级并留下可诊断的痕迹。
            log.warn("解析可选 IP 归属地实现失败，归属地查询将退化为返回原 IP：{}", failure.toString());
            return Optional.empty();
        }
    }

    /**
     * 业务作用：按“显式配置优先、其次内置候选”的顺序解析一次可选 IP 归属地实现。
     * 显式配置必须在首次调用任一归属地 API 前完成；进程运行期修改属性不会替换已经选定的实现。
     *
     * 参数说明: 无。
     * 返回: 首个可加载的实现类；所有候选均不可加载时返回 Optional.empty()。
     */
    private static Optional<Class<?>> resolveIpAreaClass() {
        ClassLoader loader = IpUtils.class.getClassLoader();
        String configured = System.getProperty("nasa.ip-area.class");
        if (StringUtils.isNotBlank(configured)) {
            Class<?> configuredClass = loadOptionalIpAreaClass(configured, loader);
            if (configuredClass != null) return Optional.of(configuredClass);
            // 显式配置加载失败不能与"未配置"同等静默处理：调用方明确表达了意图，
            // 静默回落到内置候选会让类名笔误表现为"配置生效但行为不对"，极难排查。
            log.warn("系统属性 nasa.ip-area.class 指定的实现 {} 无法加载，回落到内置候选", configured);
        }
        for (String candidate : IP_AREA_CANDIDATES) {
            Class<?> candidateClass = loadOptionalIpAreaClass(candidate, loader);
            if (candidateClass != null) {
                return Optional.of(candidateClass);
            }
        }
        return Optional.empty();
    }

    /**
     * 业务作用：加载一个可选归属地实现，并把<b>链接期</b>依赖不完整视为“该候选不可用”。
     * 仅捕获类链接错误；内存耗尽、线程终止等 JVM 致命错误不得伪装成可选依赖缺失。
     * <p>
     * 覆盖范围仅到链接为止：底层用 {@code initialize=false} 加载，不触发实现类的静态初始化器，
     * 因此静态初始化失败不会在这里暴露，而是推迟到首次实际调用时由 invokeResolved 兜住。
     *
     * @param className 候选实现的全限定类名
     * @param loader 与 nasa-core 一致的类加载器
     * 返回: 可安全链接的实现类；类不存在或其链接期依赖不完整时返回 null。
     */
    private static Class<?> loadOptionalIpAreaClass(String className, ClassLoader loader) {
        try {
            return ReflectUtils.forName(true, className, loader);
        } catch (LinkageError unavailable) {
            return null;
        }
    }

    /**
     * 业务作用：委派一个无参静态方法；先判断实现是否存在，避免缺失路径创建空 varargs 数组。
     *
     * @param method 目标静态方法名
     * @param parameterTypes 目标方法的形参类型
     * 返回: 外部实现的返回值；实现类不存在或调用失败时返回 null。
     */
    private static <T> T invokeIpArea(String method, Class<?>[] parameterTypes) {
        Optional<Class<?>> clazz = ipAreaClass();
        if (clazz.isEmpty()) return null;
        return invokeResolved(clazz.get(), method, parameterTypes);
    }

    /**
     * 业务作用：委派一个单参数静态方法；只在实现存在时才为底层反射调用创建 varargs 数组。
     *
     * @param method 目标静态方法名
     * @param parameterTypes 目标方法的形参类型
     * @param arg 调用实参
     * 返回: 外部实现的返回值；实现类不存在或调用失败时返回 null。
     */
    private static <T> T invokeIpArea(String method, Class<?>[] parameterTypes, Object arg) {
        Optional<Class<?>> clazz = ipAreaClass();
        if (clazz.isEmpty()) return null;
        return invokeResolved(clazz.get(), method, parameterTypes, arg);
    }

    /**
     * 业务作用：委派一个双参数静态方法；只在实现存在时才为底层反射调用创建 varargs 数组。
     *
     * @param method 目标静态方法名
     * @param parameterTypes 目标方法的形参类型
     * @param first 第一个调用实参
     * @param second 第二个调用实参
     * 返回: 外部实现的返回值；实现类不存在或调用失败时返回 null。
     */
    private static <T> T invokeIpArea(String method, Class<?>[] parameterTypes, Object first, Object second) {
        Optional<Class<?>> clazz = ipAreaClass();
        if (clazz.isEmpty()) return null;
        return invokeResolved(clazz.get(), method, parameterTypes, first, second);
    }

    /** 已解析的可选实现方法缓存，避免实现存在时每次调用都做一次方法查找。 */
    private static final Map<String, Optional<Method>> IP_AREA_METHODS = new ConcurrentHashMap<>();

    /**
     * 业务作用：定位并缓存可选实现的静态方法，使实现存在时不必逐次做方法查找。
     * 方法名在本类内与形参类型一一对应，因此仅以方法名作为缓存键是安全的。
     *
     * @param clazz 已解析的实现类
     * @param method 目标静态方法名
     * @param parameterTypes 目标方法的形参类型
     * 返回: 可调用的方法；实现未提供该方法时返回 {@link Optional#empty()}，该结论同样被缓存。
     */
    private static Optional<Method> ipAreaMethod(Class<?> clazz, String method, Class<?>[] parameterTypes) {
        Optional<Method> cached = IP_AREA_METHODS.get(method);
        if (cached != null) return cached;
        Optional<Method> resolved;
        try {
            Method m = clazz.getDeclaredMethod(method, parameterTypes);
            m.setAccessible(true);
            resolved = Optional.of(m);
        } catch (Exception | LinkageError unavailable) {
            resolved = Optional.empty();
        }
        IP_AREA_METHODS.putIfAbsent(method, resolved);
        return resolved;
    }

    /**
     * 业务作用：执行已经确认存在的可选实现方法，保持“归属地增强失败不阻断核心业务”的降级契约，
     * 同时保证进程级故障不被伪装成“组件缺失”。
     * <p>
     * 这里刻意不走 {@link ReflectUtils#invokeStatic}：该方法内部 {@code catch (Throwable)} 后统一
     * 包成 {@code ReflectException}，会把 {@code OutOfMemoryError} 这类致命错误洗成普通运行时异常，
     * 使本层无论如何都无法区分“可选能力不可用”和“进程已经不可用”；且 {@code ReflectException}
     * 构造时会把整个调用栈打进 error 日志，实现存在但持续失败（例如归属地服务宕机）时，
     * 每次查询都会刷一条堆栈，与本类要解决的日志放大问题同源。
     * <p>
     * 因此改为直接反射并精确分流：业务方法抛出的异常经 {@code InvocationTargetException} 拆包后，
     * 属于 {@link VirtualMachineError} 的原样上抛，其余一律静默降级。
     * @param clazz 已解析的实现类
     * @param method 目标静态方法名
     * @param parameterTypes 目标方法的形参类型
     * @param args 调用实参
     * 返回: 外部实现的返回值；方法不存在或调用抛出普通异常时返回 null；
     *      {@code VirtualMachineError} 直接向调用方传播，不在此拦截。
     */
    @SuppressWarnings("unchecked")
    private static <T> T invokeResolved(
            Class<?> clazz,
            String method,
            Class<?>[] parameterTypes,
            Object... args
    ) {
        Optional<Method> target = ipAreaMethod(clazz, method, parameterTypes);
        if (target.isEmpty()) return null;
        try {
            return (T) target.get().invoke(null, args);
        } catch (InvocationTargetException invoked) {
            // 业务方法自身抛出的错误藏在 cause 里；JVM 致命错误必须穿透降级层。
            Throwable cause = invoked.getCause();
            if (cause instanceof VirtualMachineError fatal) throw fatal;
            return null;
        } catch (Exception | LinkageError ignore) {
            return null;
        }
    }

}
