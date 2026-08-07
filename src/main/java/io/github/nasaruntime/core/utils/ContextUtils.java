package io.github.nasaruntime.core.utils;

import io.github.nasaruntime.core.base.AppCollector;
import io.github.nasaruntime.core.function.FunctionUtils;
import io.github.nasaruntime.core.function.SerFunction;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 提供进程内实例注册、配置读取和接口元信息查询能力。
 */
@SuppressWarnings("unused")
public final class ContextUtils {

    private static final Pattern PATH_VARIABLE = Pattern.compile("^\\{[a-zA-Z][a-zA-Z0-9]*}$");
    private static final ConcurrentMap<String, Object> INSTANCES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, AppCollector> REST_URL_MAP = new ConcurrentHashMap<>();

    /**
     * 业务作用：构造实例。字段取默认值。
     *
     * 参数说明: 无。
     * 返回: 构造完成后可直接使用的实例。
     */
    private ContextUtils() {
    }

    /**
     * 业务作用: 注册 REST 接口元信息，供运行时按请求路径定位对应对象和方法。
     *
     * @param restfulRouters 控制器级路由地址
     * @param interfaceRouters 方法级路由地址
     * @param target 接口所属对象
     * @param method 接口方法
     * 返回: 无；有效路由会以首次注册的数据为准。
     */
    public static void registerRestUrl(
            String[] restfulRouters, String[] interfaceRouters, Object target, Method method) {
        if (ColUtils.isEmpty(interfaceRouters)) {
            return;
        }
        for (String interfaceRouter : interfaceRouters) {
            String normalizedInterface = normalizeInterfaceRouter(interfaceRouter);
            if (ColUtils.isEmpty(restfulRouters)) {
                if (!StringUtils.Mark_right_slash.equals(normalizedInterface)) {
                    REST_URL_MAP.putIfAbsent(
                            normalizedInterface,
                            new AppCollector(StringUtils.EMPTY, normalizedInterface, target, method));
                }
                continue;
            }
            for (String restfulRouter : restfulRouters) {
                String normalizedRoot = normalizeRootRouter(restfulRouter);
                String url = normalizedRoot + normalizedInterface;
                if (!StringUtils.Mark_right_slash.equals(url)) {
                    REST_URL_MAP.putIfAbsent(
                            url, new AppCollector(normalizedRoot, normalizedInterface, target, method));
                }
            }
        }
    }

    /**
     * 业务作用: 统一方法级路由格式，确保路由索引始终使用绝对路径。
     *
     * @param router 原始方法级路由
     * @return 以斜杠开头的路由
     */
    private static String normalizeInterfaceRouter(String router) {
        Objects.requireNonNull(router, "interface router");
        return router.startsWith(StringUtils.Mark_right_slash)
                ? router
                : StringUtils.Mark_right_slash + router;
    }

    /**
     * 业务作用: 统一控制器级路由格式，避免拼接后出现重复的尾部斜杠。
     *
     * @param router 原始控制器级路由
     * @return 以斜杠开头且不以斜杠结尾的路由
     */
    private static String normalizeRootRouter(String router) {
        Objects.requireNonNull(router, "root router");
        String normalized = router.startsWith(StringUtils.Mark_right_slash)
                ? router
                : StringUtils.Mark_right_slash + router;
        if (normalized.endsWith(StringUtils.Mark_right_slash)) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 业务作用: 根据实际请求路径查找接口元信息，同时支持注册路由中的路径变量。
     *
     * @param url 不含上下文前缀的完整请求路径
     * @return 匹配的接口元信息，不存在时返回 {@code null}
     */
    public static AppCollector getAppCollector(String url) {
        AppCollector exact = REST_URL_MAP.get(url);
        if (Objects.nonNull(exact)) {
            return exact;
        }
        String[] urlLevels = ColUtils.slice(url.split(StringUtils.Mark_right_slash), 1);
        for (AppCollector collector : REST_URL_MAP.values()) {
            String[] levels = collector.getLevels();
            if (levels.length != urlLevels.length) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < levels.length; i++) {
                if (!PATH_VARIABLE.matcher(levels[i]).matches() && !levels[i].equals(urlLevels[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return collector;
            }
        }
        return null;
    }

    /**
     * 业务作用: 暴露只读路由索引，便于诊断和生成接口清单且防止外部绕过注册规则修改数据。
     * <p>
     * 参数说明: 无。
     *
     * @return 当前路由到接口元信息的只读映射
     */
    public static Map<String, AppCollector> getRestUrlMap() {
        return Collections.unmodifiableMap(new TreeMap<>(REST_URL_MAP));
    }

    /**
     * 业务作用: 注销指定路由元信息，为接口重载或测试隔离释放路由索引。
     *
     * @param url 注册时使用的完整路由模板
     * @return 被移除的接口元信息，不存在时返回 {@code null}
     */
    public static AppCollector unregisterRestUrl(String url) {
        return REST_URL_MAP.remove(url);
    }

    /**
     * 业务作用: 将共享实例注册到进程内注册表，防止同名实例被静默覆盖。
     *
     * @param name 实例名称
     * @param instance 实例对象
     * 返回: 无；同名不同实例已存在时抛出异常。
     */
    public static void registerSingleton(String name, Object instance) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(instance, "instance");
        Object existing = INSTANCES.putIfAbsent(name, instance);
        if (Objects.nonNull(existing) && existing != instance) {
            throw new IllegalStateException("Instance name already registered: " + name);
        }
    }

    /**
     * 业务作用: 从进程内注册表注销实例，为测试隔离或受控重载释放名称。
     *
     * @param name 实例名称
     * @return 被注销的实例，不存在时返回 {@code null}
     */
    public static Object unregisterSingleton(String name) {
        return INSTANCES.remove(name);
    }

    /**
     * 业务作用: 按名称读取共享实例。
     *
     * @param name 实例名称
     * @return 已注册实例，不存在时返回 {@code null}
     */
    public static Object getSingleton(String name) {
        return INSTANCES.get(name);
    }

    /**
     * 业务作用: 判断指定共享实例是否已完成注册。
     *
     * @param name 实例名称
     * @return 已注册返回 {@code true}，否则返回 {@code false}
     */
    public static boolean containsSingleton(String name) {
        return INSTANCES.containsKey(name);
    }

    /**
     * 业务作用: 返回稳定顺序的只读实例快照，避免调用方修改内部注册表。
     * <p>
     * 参数说明: 无。
     *
     * @return 按名称排序的实例只读映射
     */
    public static Map<String, Object> getAllBeans() {
        return Collections.unmodifiableMap(new TreeMap<>(INSTANCES));
    }

    /**
     * 业务作用: 按名称获取必需实例，在装配不完整时快速失败。
     *
     * @param name 实例名称
     * @return 已注册实例
     * @throws NoSuchElementException 名称未注册时抛出
     */
    public static Object getBean(String name) {
        Object instance = getBeanOrNull(name);
        if (Objects.isNull(instance)) {
            throw new NoSuchElementException("No instance registered with name: " + name);
        }
        return instance;
    }

    /**
     * 业务作用: 按名称尝试获取可选实例，允许调用方使用本地创建逻辑降级。
     *
     * @param name 实例名称
     * @return 已注册实例，不存在时返回 {@code null}
     */
    public static Object getBeanOrNull(String name) {
        return INSTANCES.get(name);
    }

    /**
     * 业务作用: 按名称和类型获取必需实例，同时校验注册对象的类型边界。
     *
     * @param name 实例名称
     * @param type 期望类型
     * @return 类型匹配的已注册实例
     * @throws NoSuchElementException 名称未注册时抛出
     * @throws ClassCastException 实例类型不匹配时抛出
     */
    public static <T> T getBean(String name, Class<T> type) {
        return type.cast(getBean(name));
    }

    /**
     * 业务作用: 按名称和类型尝试获取可选实例，类型不匹配时按不存在处理。
     *
     * @param name 实例名称
     * @param type 期望类型
     * @return 类型匹配的实例，不存在或类型不匹配时返回 {@code null}
     */
    public static <T> T getBeanOrNull(String name, Class<T> type) {
        Object instance = getBeanOrNull(name);
        return type.isInstance(instance) ? type.cast(instance) : null;
    }

    /**
     * 业务作用: 按类型获取必需实例，在无候选实例时快速失败。
     *
     * @param type 期望类型
     * @return 按名称排序后的首个类型匹配实例
     * @throws NoSuchElementException 没有匹配实例时抛出
     */
    public static <T> T getBean(Class<T> type) {
        T instance = getBeanOrNull(type);
        if (Objects.isNull(instance)) {
            throw new NoSuchElementException("No instance registered for type: " + type.getName());
        }
        return instance;
    }

    /**
     * 业务作用: 按类型尝试获取可选实例，供协议转换器等组件在未注册时回退到反射创建。
     *
     * @param type 期望类型
     * @return 按名称排序后的首个匹配实例，不存在时返回 {@code null}
     */
    public static <T> T getBeanOrNull(Class<T> type) {
        return getBeansOfType(type).values().stream().findFirst().orElse(null);
    }

    /**
     * 业务作用: 提供兼容的首实例查询语义，便于调用方表达可选依赖。
     *
     * @param type 期望类型
     * @return 首个匹配实例，不存在时返回 {@code null}
     */
    public static <T> T getBeanFirstOrNull(Class<T> type) {
        return getBeanOrNull(type);
    }

    /**
     * 业务作用: 在不依赖代理实现细节的前提下查找指定实现类型或其接口实例。
     *
     * @param implementationType 具体实现类型
     * @param contractType 接口或抽象父类类型
     * @return 匹配的注册实例
     * @throws NoSuchElementException 没有匹配实例时抛出
     */
    public static <T extends I, I> I getBeanOrProxy(
            Class<T> implementationType, Class<I> contractType) {
        T exact = getBeanOrNull(implementationType);
        if (Objects.nonNull(exact)) {
            return exact;
        }
        for (I candidate : getBeansOfType(contractType).values()) {
            if (implementationType.isAssignableFrom(ReflectUtils.targetClass(candidate))) {
                return candidate;
            }
        }
        throw new NoSuchElementException(
                "No instance registered for implementation: " + implementationType.getName());
    }

    /**
     * 业务作用: 按类型生成稳定顺序的实例快照，供扩展点发现和批量装配使用。
     *
     * @param type 期望类型
     * @return 按实例名称排序的匹配映射
     */
    public static <T> Map<String, T> getBeansOfType(Class<T> type) {
        Map<String, T> result = new LinkedHashMap<>();
        new TreeMap<>(INSTANCES).forEach((name, instance) -> {
            if (type.isInstance(instance)) {
                result.put(name, type.cast(instance));
            }
        });
        return result;
    }

    /**
     * 业务作用: 将同类型扩展实例转换为业务键映射，并按声明优先级稳定处理覆盖顺序。
     *
     * @param type 扩展接口或父类
     * @param keyMapper 业务键生成函数
     * @return 业务键到扩展实例的有序映射
     */
    public static <T, K> Map<K, T> beanMapOfType(Class<T> type, Function<T, K> keyMapper) {
        List<T> values = new ArrayList<>(getBeansOfType(type).values());
        ColUtils.ascOrder(values);
        Map<K, T> result = new LinkedHashMap<>();
        values.forEach(value -> result.put(keyMapper.apply(value), value));
        return result;
    }

    /**
     * 业务作用: 按业务键归组同类型扩展实例，并保持扩展优先级顺序。
     *
     * @param type 扩展接口或父类
     * @param keyMapper 业务键生成函数
     * @return 业务键到扩展实例列表的有序映射
     */
    public static <T, K> Map<K, ArrayList<T>> beansMapOfType(
            Class<T> type, Function<T, K> keyMapper) {
        return beansMapOfType(type, keyMapper, ArrayList::new);
    }

    /**
     * 业务作用: 使用调用方指定的集合类型归组扩展实例，兼顾顺序与结果容器定制。
     *
     * @param type 扩展接口或父类
     * @param keyMapper 业务键生成函数
     * @param collectionFactory 分组集合创建函数
     * @return 业务键到扩展实例集合的有序映射
     */
    public static <T, K, C extends Collection<T>> Map<K, C> beansMapOfType(
            Class<T> type, Function<T, K> keyMapper, Supplier<C> collectionFactory) {
        List<T> values = new ArrayList<>(getBeansOfType(type).values());
        ColUtils.ascOrder(values);
        Map<K, C> result = new LinkedHashMap<>();
        values.forEach(value -> result
                .computeIfAbsent(keyMapper.apply(value), ignored -> collectionFactory.get())
                .add(value));
        return result;
    }

    /**
     * 业务作用: 查找带指定类型注解的注册实例，供声明式扩展点发现使用。
     *
     * @param annotationType 注解类型
     * @return 按名称排序的匹配实例映射
     */
    public static Map<String, Object> getBeansWithAnnotation(
            Class<? extends Annotation> annotationType) {
        Map<String, Object> result = new LinkedHashMap<>();
        new TreeMap<>(INSTANCES).forEach((name, instance) -> {
            if (ReflectUtils.targetClass(instance).isAnnotationPresent(annotationType)) {
                result.put(name, instance);
            }
        });
        return result;
    }

    /**
     * 业务作用: 将带指定注解的实例转换为业务键映射，键既可来自注解也可来自实例。
     *
     * @param annotationType 注解类型
     * @param keyMapper 注解或实例上的业务键生成函数
     * @return 业务键到实例的有序映射
     */
    @SuppressWarnings("unchecked")
    public static <A extends Annotation, V, E, K> Map<K, V> beanMapOfAnnotation(
            Class<A> annotationType, SerFunction<E, K> keyMapper) {
        List<Object> values = new ArrayList<>(getBeansWithAnnotation(annotationType).values());
        ColUtils.ascOrder(values);
        boolean annotationMapper = keyApplierIsAnnotation(annotationType, keyMapper);
        Map<K, V> result = new LinkedHashMap<>();
        for (Object value : values) {
            E source = annotationMapper
                    ? (E) ReflectUtils.targetClass(value).getAnnotation(annotationType)
                    : (E) value;
            result.put(keyMapper.apply(source), (V) value);
        }
        return result;
    }

    /**
     * 业务作用: 按业务键归组带指定注解的实例，并使用列表承载同键扩展。
     *
     * @param annotationType 注解类型
     * @param keyMapper 注解或实例上的业务键生成函数
     * @return 业务键到实例列表的有序映射
     */
    public static <A extends Annotation, E, K, T> Map<K, ArrayList<T>> beansMapOfAnnotation(
            Class<A> annotationType, SerFunction<E, K> keyMapper) {
        return beansMapOfAnnotation(annotationType, keyMapper, ArrayList::new);
    }

    /**
     * 业务作用: 使用调用方指定集合类型归组带注解实例，键可来自注解属性或实例方法。
     *
     * @param annotationType 注解类型
     * @param keyMapper 注解或实例上的业务键生成函数
     * @param collectionFactory 分组集合创建函数
     * @return 业务键到实例集合的有序映射
     */
    @SuppressWarnings("unchecked")
    public static <A extends Annotation, E, K, T, V extends Collection<T>>
            /**
             * 业务作用：扫描并实例化带指定注解的类，按类型索引，供以约定优于配置的方式装配可插拔实现。
             *
             * @param annotationType 见上述说明
             * @param keyMapper 元素到键的映射函数
             * @param collectionFactory 见上述说明
             * 返回: 类型到实例的映射。
             */
            Map<K, V> beansMapOfAnnotation(
                    Class<A> annotationType,
                    SerFunction<E, K> keyMapper,
                    Supplier<V> collectionFactory) {
        List<Object> values = new ArrayList<>(getBeansWithAnnotation(annotationType).values());
        ColUtils.ascOrder(values);
        boolean annotationMapper = keyApplierIsAnnotation(annotationType, keyMapper);
        Map<K, V> result = new LinkedHashMap<>();
        for (Object value : values) {
            E source = annotationMapper
                    ? (E) ReflectUtils.targetClass(value).getAnnotation(annotationType)
                    : (E) value;
            result.computeIfAbsent(keyMapper.apply(source), ignored -> collectionFactory.get())
                    .add((T) value);
        }
        return result;
    }

    /**
     * 业务作用: 判断序列化方法引用是否读取注解属性，以便选择正确的键生成输入对象。
     *
     * @param annotationType 注解类型
     * @param keyMapper 键生成方法引用
     * @return 方法引用声明在注解类型上时返回 {@code true}
     */
    private static <T, E, K> boolean keyApplierIsAnnotation(
            Class<T> annotationType, SerFunction<E, K> keyMapper) {
        return annotationType.getName().equals(
                FunctionUtils.serializedLambda(keyMapper)
                        .getImplClass()
                        .replace(StringUtils.Mark_right_slash, StringUtils.Mark_spot));
    }

    /**
     * 业务作用: 读取可选配置，为对象池容量和运行时开关提供统一入口。
     *
     * @param key 配置键
     * @return 配置值，不存在时返回 {@code null}
     */
    public static String getProperty(String key) {
        return getProperty(key, null);
    }

    /**
     * 业务作用: 按系统属性、原始环境变量、规范化环境变量的优先级读取配置。
     *
     * @param key 配置键
     * @param defaultValue 配置不存在时的默认值
     * @return 解析出的配置值或默认值
     */
    public static String getProperty(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (Objects.nonNull(value)) {
            return value;
        }
        value = System.getenv(key);
        if (Objects.nonNull(value)) {
            return value;
        }
        String environmentKey = key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        return System.getenv().getOrDefault(environmentKey, defaultValue);
    }

    /**
     * 业务作用: 提供不会依赖外部配置容器的安全配置读取入口。
     *
     * @param key 配置键
     * @param defaultValue 配置不存在时的默认值
     * @return 配置值或默认值
     */
    public static String getPropertySafe(String key, String defaultValue) {
        return getProperty(key, defaultValue);
    }

    /**
     * 业务作用: 读取整数配置，格式错误时回退默认值以避免辅助容量参数阻断启动。
     *
     * @param key 配置键
     * @param defaultValue 默认整数
     * @return 解析后的整数或默认值
     */
    public static int getPropertyInt(String key, int defaultValue) {
        String value = getPropertySafe(key, null);
        try {
            return StringUtils.isBlank(value) ? defaultValue : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /**
     * 业务作用: 读取长整数配置，格式错误时回退默认值以维持运行时安全边界。
     *
     * @param key 配置键
     * @param defaultValue 默认长整数
     * @return 解析后的长整数或默认值
     */
    public static long getPropertyLong(String key, long defaultValue) {
        String value = getPropertySafe(key, null);
        try {
            return StringUtils.isBlank(value) ? defaultValue : Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /**
     * 业务作用: 读取布尔配置，为运行时开关提供明确的默认行为。
     *
     * @param key 配置键
     * @param defaultValue 默认布尔值
     * @return 解析后的布尔值或默认值
     */
    public static boolean getPropertyBool(String key, boolean defaultValue) {
        String value = getPropertySafe(key, null);
        return StringUtils.isBlank(value) ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    /**
     * 业务作用: 判断进程内注册表是否已有实例，用于识别显式装配是否开始。
     * <p>
     * 参数说明: 无。
     *
     * @return 至少存在一个注册实例时返回 {@code true}
     */
    public static boolean isActive() {
        return !INSTANCES.isEmpty();
    }
}
