package io.github.nasaruntime.core.utils;

import io.github.nasaruntime.core.enums.Sort;
import io.github.nasaruntime.core.exception.JsonException;
import io.github.nasaruntime.core.function.FunctionUtils;
import io.github.nasaruntime.core.function.SerFunction;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Nasa
 */
@SuppressWarnings({"unused", "rawtypes", "unchecked"})
@Slf4j
public abstract class MapUtils {

	private static final Map<Class<?>, Class<?>> WRAPPER_TO_PRIMITIVE = Map.of(
			Integer.class, int.class, Long.class, long.class,
			Double.class, double.class, Float.class, float.class,
			Boolean.class, boolean.class, Byte.class, byte.class,
			Short.class, short.class, Character.class, char.class
	);

	/**
	 * 业务作用：判断map为空
	 *
	 * @param map 见上述说明
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isEmpty(Map<?, ?> map) {
		return Objects.isNull(map) || map.isEmpty();
	}

	/**
	 * 业务作用：判断map不为空
	 *
	 * @param map 见上述说明
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isNotEmpty(Map<?, ?> map) {
		return !isEmpty(map);
	}

	/**
	 * 业务作用：把对象通过 getter 转成 LinkedHashMap，保留属性声明顺序，值为 null 的属性被忽略。
	 *
	 * @param obj 源对象
	 * @param getters 取值函数数组
	 * 返回: 保序的属性映射。
	 */
	@SafeVarargs
	public static <T, R> LinkedHashMap<String, Object> toLinkedMap(T obj, SerFunction<T, R>... getters) {
		return (LinkedHashMap<String, Object>) toMap(obj, new LinkedHashMap<>(), getters);
	}

	/**
	 * 业务作用：把对象通过 getter 转成 HashMap，值为 null 的属性被忽略。
	 *
	 * @param obj 源对象
	 * @param getters 取值函数数组
	 * 返回: 属性映射；键顺序不保证。
	 */
	@SafeVarargs
	public static <T, R> HashMap<String, Object> toHashMap(T obj, SerFunction<T, R>... getters) {
		return (HashMap<String, Object>) toMap(obj, new HashMap<>(), getters);
	}

	/**
	 * 业务作用：把对象通过 getter 转成映射，值为 null 的属性被忽略而不是写入 null。
	 *
	 * @param obj 源对象
	 * @param map 源映射
	 * @param getters 取值函数数组
	 * 返回: 属性名到属性值的映射。
	 */
	@SafeVarargs
	public static <T> Map<String, Object> toMap(T obj, Map<String, Object> map, SerFunction<T, ?>... getters) {
		if (Objects.isNull(obj)) {
			return map;
		}
		Class<?> clazz = obj.getClass();
		if (obj.getClass() == Object.class) {
			return map;
		}
		try {
			if (ColUtils.isNotEmpty(getters)) {
				// 指定了getter方法
				for (SerFunction<T, ?> getter : getters) {

					if (Objects.isNull(getter)) {
						continue;
					}

					Object val = getter.apply(obj);

					if (Objects.nonNull(val)) {

						map.put(FunctionUtils.fieldName(getter), val);
					}
				}
				return map;
			}

			List<Method> allGetter = ReflectUtils.allGetter(clazz);

			for (Method method : allGetter) {

				// 没有指定getter方法

				Object val = method.invoke(obj);

				if (Objects.nonNull(val)) {

					map.put(ReflectUtils.fieldName(method), val);
				}
			}

			return map;

		} catch (IllegalAccessException | InvocationTargetException e) {
			throw new JsonException(e.getMessage(), e);
		}

	}

	/**
	 * 业务作用：把对象通过 getter 转成 LinkedHashMap，保留属性声明顺序，值为 null 的属性被忽略。
	 *
	 * @param sourceMap 见上述说明
	 * @param keys 键集合
	 * 返回: 保序的属性映射。
	 */
	public static <T> Map<String, T> toLinkedMap(Map<String, T> sourceMap, String... keys) {
		return toMap(sourceMap, new LinkedHashMap<>(), keys);
	}

	/**
	 * 业务作用：把对象通过 getter 转成 HashMap，值为 null 的属性被忽略。
	 *
	 * @param sourceMap 见上述说明
	 * @param keys 键集合
	 * 返回: 属性映射；键顺序不保证。
	 */
	public static <T> Map<String, T> toHashMap(Map<String, T> sourceMap, String... keys) {
		return toMap(sourceMap, new HashMap<>(), keys);
	}

	/**
	 * 业务作用：把对象通过 getter 转成映射，值为 null 的属性被忽略而不是写入 null。
	 *
	 * @param sourceMap 见上述说明
	 * @param targetMap 见上述说明
	 * @param keys 键集合
	 * 返回: 属性名到属性值的映射。
	 */
	public static <T> Map<String, T> toMap(Map<String, T> sourceMap, Map<String, T> targetMap, String... keys) {
		if (ColUtils.isNotEmpty(keys)) {
			for (String key : keys) {
				T obj = sourceMap.get(key);
				if (Objects.isNull(obj)) {
					continue;
				}
				targetMap.put(key, obj);
			}
			return targetMap;
		}
		targetMap.putAll(sourceMap);
		return targetMap;
	}

	/**
	 * 业务作用：把映射通过 setter 回填到目标对象，值为 null 的项被忽略而不会覆盖对象已有值。
	 *
	 * @param map 源映射
	 * @param clazz 目标类型
	 * 返回: 回填后的对象。
	 */
	public static <T> T toEntity(Map<String, Object> map, Class<T> clazz) {
		try {
			T obj = ReflectUtils.newInstance(clazz);
			toEntity(map, obj);
			return obj;
		} catch (Exception e) {
			throw new JsonException(e.getMessage(), e);
		}
	}

	/**
	 * 业务作用：将map转为指定对象，通过setter赋值，值为null时忽略
	 *
	 * @param map 源map对象
	 * @param obj 目标对象
	 * 返回: 无返回值。
	 */
	public static <T> void toEntity(Map<String, Object> map, T obj) {
		try {
			Class<?> clazz = obj.getClass();
			for (Map.Entry<String, Object> entry : map.entrySet()) {

				if (Objects.isNull(entry.getValue())) {
					continue;
				}

				String key = entry.getKey();
				if (StringUtils.isBlank(key)) {
					continue;
				}

				String setterName = ReflectUtils.toSetterName(key);
				Class<?> valClass = entry.getValue().getClass();
				try {
					Method method = clazz.getMethod(setterName, valClass);
					method.invoke(obj, entry.getValue());
				} catch (NoSuchMethodException ignored) {
					// 包装类型匹配不到 → 尝试基本类型 (Integer→int, Long→long 等)
					try {
						Class<?> pt = WRAPPER_TO_PRIMITIVE.get(valClass);
						if (pt != null) {
							Method method = clazz.getMethod(setterName, pt);
							method.invoke(obj, entry.getValue());
						}
					} catch (NoSuchMethodException ignored2) {
						// setter 参数类型完全不匹配, 跳过
					}
				}
			}
		} catch (Exception e) {
			throw new JsonException(e.getMessage(), e);
		}
	}

	/**
	 * 业务作用：把映射转换为 ConcurrentMap，供后续并发读写。
	 *
	 * @param map 源映射
	 * 返回: 线程安全的映射；注意其不接受 null 键与 null 值。
	 */
	public static <K, V> ConcurrentMap<K, V> toConcurrentMap(Map<K, V> map) {
		if (Objects.isNull(map)) {
			return null;
		}
		if (map instanceof ConcurrentMap) {
			return (ConcurrentMap<K, V>) map;
		}
		if (map instanceof SortedMap) {
			// key是有序的
			// 添加、删除、查找操作都是基于跳表结构（Skip List）实现
			// key和value都不能为null
			return new ConcurrentSkipListMap<>((SortedMap<K, V>) map);
		}
		return new ConcurrentHashMap<>(map);
	}

	/**
	 * 业务作用：把对象通过 getter 转成 HashMap，值为 null 的属性被忽略。
	 *
	 * @param kvs 见上述说明
	 * 返回: 属性映射；键顺序不保证。
	 */
	public static <K, V> HashMap<K, V> toHashMap(Object... kvs) {
		return toMap((Supplier<HashMap<K,V>>) HashMap::new, kvs);
	}

	/**
	 * 业务作用：把对象通过 getter 转成 LinkedHashMap，保留属性声明顺序，值为 null 的属性被忽略。
	 *
	 * @param kvs 见上述说明
	 * 返回: 保序的属性映射。
	 */
	public static <K, V> LinkedHashMap<K, V> toLinkedMap(Object... kvs) {
		return toMap((Supplier<LinkedHashMap<K,V>>) LinkedHashMap::new, kvs);
	}

	/**
	 * 业务作用：把对象通过 getter 转成映射，值为 null 的属性被忽略而不是写入 null。
	 *
	 * @param supplier 目标容器的构造器
	 * @param kvs 见上述说明
	 * 返回: 属性名到属性值的映射。
	 */
	public static <K, V, R extends Map<K, V>> R toMap(Supplier<R> supplier, Object... kvs) {
		return toMap(supplier.get(), kvs);
	}

	/**
	 * 业务作用：把对象通过 getter 转成映射，值为 null 的属性被忽略而不是写入 null。
	 *
	 * @param map 源映射
	 * @param kvs 见上述说明
	 * 返回: 属性名到属性值的映射。
	 */
	@SuppressWarnings("unchecked")
	public static <K, V, R extends Map<K, V>> R toMap(R map, Object... kvs) {
		if ((kvs.length & 1) != 0) {
			throw new IllegalArgumentException("kvs length must be even, got " + kvs.length);
		}
		int pairs = kvs.length >>> 1;
		for (int i = 0; i < pairs; i++) {
			int index = i << 1;
			map.put((K) kvs[index], (V) kvs[index + 1]);
		}
		return map;
	}

	/**
	 * 业务作用：把映射包装成不可修改视图，用于对外暴露只读配置。
	 *
	 * @param map 源映射
	 * 返回: 不可修改映射；任何修改操作都会抛 UnsupportedOperationException。
	 */
	public static <K, V> Map<K, V> unmodifiableMap(Map<K, V> map) {
		if (Objects.isNull(map)) {
			return null;
		}
		if (map instanceof NavigableMap) {
			return Collections.unmodifiableNavigableMap((NavigableMap<K, V>) map);
		}
		if (map instanceof SortedMap) {
			return Collections.unmodifiableSortedMap((SortedMap<K, V>) map);
		}
		return Collections.unmodifiableMap(map);
	}

	/**
	 * 业务作用：从映射中删除键，并容忍映射本身为 null，省去调用方判空。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 被删除的值；映射为 null 或键不存在时返回 null。
	 */
	public static <V> V remove(Map map, Object key) {
		return map == null ? null : (V) map.remove(key);
	}

	/**
	 * 业务作用：替map执行forEach，忽略null
	 *
	 * @param map 见上述说明
	 * @param action 见上述说明
	 * 返回: 无返回值。
	 */
	public static <K, V> void forEach(Map<K, V> map, BiConsumer<? super K, ? super V> action) {
		if (map != null && action != null) map.forEach(action);
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Object，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 Object；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K, V> V getObject(Map map, K key) {
		return Objects.isNull(map) ? null : (V) map.get(key);
	}

	/**
	 * 业务作用：按键取值并用给定函数转换类型。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param mapper 元素到目标值的映射函数
	 * 返回: 转换后的值；键不存在时返回默认值或 null。
	 */
	public static <K, V, E> E getObjectMapper(Map map, K key, Function<V, E> mapper) {
		return mapper.apply(getObject(map, key));
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 String，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 String；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> String getString(Map map, K key) {
		Object obj = getObject(map, key);
		return Objects.isNull(obj) ? null : ObjMprUtils.toString(obj);
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Boolean，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 Boolean；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Boolean getBoolean(Map map, K key) {
		Object obj = getObject(map, key);
		if (Objects.isNull(obj)) {
			return null;
		}
        return switch (obj) {
            case Boolean b -> b;
            case String s -> Boolean.parseBoolean(s);
            case AtomicBoolean ab -> ab.get();
            default -> throw new JsonException("Unparseable Boolean: {}", ObjMprUtils.toString(obj));
        };
    }

	/**
	 * 业务作用：按键从映射中取值并转换成 Number，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 Number；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Number getNumber(Map map, K key) {
		Object obj = getObject(map, key);
		if (Objects.isNull(obj)) {
			return null;
		}
		return switch (obj) {
			case Number n -> n;
			case String s -> {
				try {
					// 优先尝试 Long (整数场景更常见, 避免精度丢失)
					yield Long.parseLong(s);
				} catch (NumberFormatException e) {
					try {
						yield Double.parseDouble(s);
					} catch (NumberFormatException e2) {
						throw new JsonException("Unparseable number: {}", s);
					}
				}
			}
			default -> throw new JsonException("Unparseable number: {}", ObjMprUtils.toString(obj));
		};
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Long，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 Long；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Long getLong(Map map, K key) {
		Number number = getNumber(map, key);
		return Objects.isNull(number) ? null : number.longValue();
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Integer，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 Integer；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Integer getInteger(Map map, K key) {
		Number number = getNumber(map, key);
		return Objects.isNull(number) ? null : number.intValue();
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Byte，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 Byte；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Byte getByte(Map map, K key) {
		Number number = getNumber(map, key);
		return Objects.isNull(number) ? null : number.byteValue();
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Short，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 Short；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Short getShort(Map map, K key) {
		Number number = getNumber(map, key);
		return Objects.isNull(number) ? null : number.shortValue();
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Double，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 Double；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Double getDouble(Map map, K key) {
		Number number = getNumber(map, key);
		return Objects.isNull(number) ? null : number.doubleValue();
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Float，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 Float；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Float getFloat(Map map, K key) {
		Number number = getNumber(map, key);
		return Objects.isNull(number) ? null : number.floatValue();
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 BigDecimal，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 BigDecimal；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> BigDecimal getBigDecimal(Map map, K key) {
		Object obj = getObject(map, key);
		if (Objects.isNull(obj)) {
			return null;
		}
		return switch (obj) {
			case BigDecimal bd -> bd;
			case String s -> new BigDecimal(s);
			case Number n -> new BigDecimal(n.toString());
			default -> null;
		};
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 BigInteger，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 BigInteger；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> BigInteger getBigInteger(Map map, K key) {
		Object obj = getObject(map, key);
		if (Objects.isNull(obj)) {
			return null;
		}
		return switch (obj) {
			case BigInteger bi -> bi;
			case String s -> new BigInteger(s);
			case Number n -> BigInteger.valueOf(n.longValue());
			default -> null;
		};
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 AtomicInteger，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 AtomicInteger；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> AtomicInteger getAtomicInteger(Map map, K key) {
		Object obj = getObject(map, key);
		if (Objects.isNull(obj)) {
			return null;
		}
		if (obj instanceof AtomicInteger) {
			return (AtomicInteger) obj;
		}
		if (obj instanceof Number num) {
			return new AtomicInteger(num.intValue());
		}
		try {
			return obj instanceof String os ? new AtomicInteger(Integer.parseInt(os)) : null;
		} catch (Exception e) {
			throw new JsonException(e.getMessage(), e);
		}
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 AtomicLong，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 AtomicLong；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> AtomicLong getAtomicLong(Map map, K key) {
		Object obj = getObject(map, key);
		if (Objects.isNull(obj)) {
			return null;
		}
		if (obj instanceof AtomicLong) {
			return (AtomicLong) obj;
		}
		if (obj instanceof Number num) {
			return new AtomicLong(num.longValue());
		}
		try {
			return obj instanceof String os ? new AtomicLong(Long.parseLong(os)) : null;
		} catch (Exception e) {
			throw new JsonException(e.getMessage(), e);
		}
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 AtomicBoolean，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * 返回: 转换后的 AtomicBoolean；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> AtomicBoolean getAtomicBoolean(Map map, K key) {
		Object obj = getObject(map, key);
		if (Objects.isNull(obj)) {
			return null;
		}
		if (obj instanceof AtomicBoolean) {
			return (AtomicBoolean) obj;
		}
		if (obj instanceof Boolean bool) {
			return new AtomicBoolean(bool);
		}
		try {
			return obj instanceof String os ? new AtomicBoolean(Boolean.parseBoolean(os)) : null;
		} catch (Exception e) {
			throw new JsonException(e.getMessage(), e);
		}
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Object，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 Object；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K, V> V getObject(Map map, K key, V dftVal) {
		V obj = getObject(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 String，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 String；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> String getString(Map map, K key, String dftVal) {
		String obj = getString(map, key);
		return StringUtils.isBlank(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Boolean，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 Boolean；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Boolean getBoolean(Map map, K key, boolean dftVal) {
		Boolean obj = getBoolean(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Number，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 Number；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Number getNumber(Map map, K key, Number dftVal) {
		Number obj = getNumber(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Long，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 Long；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Long getLong(Map map, K key, long dftVal) {
		Long obj = getLong(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Integer，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 Integer；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Integer getInteger(Map map, K key, int dftVal) {
		Integer obj = getInteger(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Byte，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 Byte；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Byte getByte(Map map, K key, byte dftVal) {
		Byte obj = getByte(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Short，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 Short；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Short getShort(Map map, K key, short dftVal) {
		Short obj = getShort(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Double，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 Double；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Double getDouble(Map map, K key, double dftVal) {
		Double obj = getDouble(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Float，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 Float；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Float getFloat(Map map, K key, float dftVal) {
		Float obj = getFloat(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 BigDecimal，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 BigDecimal；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> BigDecimal getBigDecimal(Map map, K key, BigDecimal dftVal) {
		BigDecimal obj = getBigDecimal(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 BigInteger，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 BigInteger；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> BigInteger getBigInteger(Map map, K key, BigInteger dftVal) {
		BigInteger obj = getBigInteger(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 AtomicInteger，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 AtomicInteger；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> AtomicInteger getAtomicInteger(Map map, K key, AtomicInteger dftVal) {
		AtomicInteger obj = getAtomicInteger(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 AtomicLong，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 AtomicLong；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> AtomicLong getAtomicLong(Map map, K key, AtomicLong dftVal) {
		AtomicLong obj = getAtomicLong(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 AtomicBoolean，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param dftVal 见上述说明
	 * 返回: 转换后的 AtomicBoolean；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> AtomicBoolean getAtomicBoolean(Map map, K key, AtomicBoolean dftVal) {
		AtomicBoolean obj = getAtomicBoolean(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 业务作用：判断map中key是否是true
	 *
	 * @param map 见上述说明
	 * @param key 见上述说明
	 * @param dft 见上述说明
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static <K> boolean isTrue(Map map, K key, boolean dft) {
		return getBoolean(map, key, dft);
	}

	/**
	 * 业务作用：判断map中key是否是false
	 *
	 * @param map 见上述说明
	 * @param key 见上述说明
	 * @param dft 见上述说明
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static <K> boolean isFalse(Map map, K key, boolean dft) {
		Boolean b = getBoolean(map, key);
		return Objects.isNull(b) ? dft : !b;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Object，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 Object；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K, V> V getObject(Map map, K key, Function<K, V> getter) {
		V obj = getObject(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 String，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 String；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> String getString(Map map, K key, Function<K, String> getter) {
		String obj = getString(map, key);
		return StringUtils.isBlank(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Boolean，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 Boolean；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Boolean getBoolean(Map map, K key, Function<K, Boolean> getter) {
		Boolean obj = getBoolean(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Number，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 Number；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Number getNumber(Map map, K key, Function<K, Number> getter) {
		Number obj = getNumber(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Long，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 Long；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Long getLong(Map map, K key, Function<K, Long> getter) {
		Long obj = getLong(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Integer，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 Integer；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Integer getInteger(Map map, K key, Function<K, Integer> getter) {
		Integer obj = getInteger(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Byte，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 Byte；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Byte getByte(Map map, K key, Function<K, Byte> getter) {
		Byte obj = getByte(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Short，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 Short；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Short getShort(Map map, K key, Function<K, Short> getter) {
		Short obj = getShort(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Double，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 Double；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Double getDouble(Map map, K key, Function<K, Double> getter) {
		Double obj = getDouble(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 Float，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 Float；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> Float getFloat(Map map, K key, Function<K, Float> getter) {
		Float obj = getFloat(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 BigDecimal，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 BigDecimal；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> BigDecimal getBigDecimal(Map map, K key, Function<K, BigDecimal> getter) {
		BigDecimal obj = getBigDecimal(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 BigInteger，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 BigInteger；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> BigInteger getBigInteger(Map map, K key, Function<K, BigInteger> getter) {
		BigInteger obj = getBigInteger(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 AtomicInteger，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 AtomicInteger；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> AtomicInteger getAtomicInteger(Map map, K key, Function<K, AtomicInteger> getter) {
		AtomicInteger obj = getAtomicInteger(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 AtomicLong，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 AtomicLong；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> AtomicLong getAtomicLong(Map map, K key, Function<K, AtomicLong> getter) {
		AtomicLong obj = getAtomicLong(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按键从映射中取值并转换成 AtomicBoolean，同时容忍映射为 null 与键不存在，省去调用方逐处判空与强转。
	 *
	 * @param map 源映射
	 * @param key 键
	 * @param getter 取值函数
	 * 返回: 转换后的 AtomicBoolean；映射为 null、键不存在或无法转换时返回默认值（未提供默认值时为 null）。
	 */
	public static <K> AtomicBoolean getAtomicBoolean(Map map, K key, Function<K, AtomicBoolean> getter) {
		AtomicBoolean obj = getAtomicBoolean(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 业务作用：按条件查找并返回首个命中的条目。
	 *
	 * @param map 源映射
	 * @param predicate 筛选条件，返回 true 才纳入结果
	 * 返回: 首个命中条目；无命中返回 null。
	 */
	public static <K, V> LinkedHashMap<K, V> getPredicate(Map<K, V> map, BiPredicate<K, V> predicate) {
		LinkedHashMap<K, V> rm = new LinkedHashMap<>();
		map.forEach((k, v) -> {
			if (predicate.test(k, v)) rm.put(k, v);
		});
		return rm;
	}

	/**
	 * 业务作用：按条件查找并返回全部命中的值。
	 *
	 * @param map 源映射
	 * @param predicate 筛选条件，返回 true 才纳入结果
	 * 返回: 命中的值集合；无命中返回空集合。
	 */
	public static <K, V> ArrayList<V> getValuePredicate(Map<K, V> map, BiPredicate<K, V> predicate) {
		ArrayList<V> list = new ArrayList<>();
		map.forEach((k, v) -> {
			if (predicate.test(k, v)) list.add(v);
		});
		return list;
	}

	/**
	 * 业务作用：按条件查找并返回首个命中的值，命中即停止遍历。
	 *
	 * @param map 源映射
	 * @param predicate 筛选条件，返回 true 才纳入结果
	 * 返回: 首个命中的值；无命中返回 null。
	 */
	public static <K, V> V getValuePredicateFirst(Map<K, V> map, BiPredicate<K, V> predicate) {
		for (Map.Entry<K, V> entry : map.entrySet()) {
			if (predicate.test(entry.getKey(), entry.getValue())) return entry.getValue();
		}
		return null;
	}

	/**
	 * 业务作用：按条件查找并返回首个命中的值，命中即停止遍历。
	 *
	 * @param map 源映射
	 * @param predicate 筛选条件，返回 true 才纳入结果
	 * @param dft 取不到时返回的默认值
	 * 返回: 首个命中的值；无命中返回 null。
	 */
	public static <K, V> V getValuePredicateFirst(Map<K, V> map, BiPredicate<K, V> predicate, V dft) {
		V v = getValuePredicateFirst(map, predicate);
		return Objects.isNull(v) ? dft : v;
	}

	/**
	 * 业务作用：按给定比较规则对映射排序并输出保序映射。
	 *
	 * @param map 源映射
	 * @param mapping 见上述说明
	 * @param sort 见上述说明
	 * 返回: 按指定顺序排列的 LinkedHashMap。
	 */
	public static <K, V, R extends Comparable<R>> LinkedHashMap<K, V> sort(
			Map<K, V> map, Function<Map.Entry<K, V>, R> mapping, Sort sort) {
		List<Map.Entry<K, V>> entries = new ArrayList<>(map.entrySet());
		ColUtils.sortType(entries, mapping, sort);
		LinkedHashMap<K, V> result = new LinkedHashMap<>();
		for (Map.Entry<K, V> entry : entries) {
			result.put(entry.getKey(), entry.getValue());
		}
		return result;
	}

	/**
	 * 业务作用：按键升序输出保序映射。
	 *
	 * @param map 源映射
	 * 返回: 按键升序排列的 LinkedHashMap。
	 */
	public static <K extends Comparable<K>, V> LinkedHashMap<K, V> ascKey(Map<K, V> map) {
		return sort(map, Map.Entry::getKey, Sort.ASC);
	}

	/**
	 * 业务作用：按键降序输出保序映射。
	 *
	 * @param map 源映射
	 * 返回: 按键降序排列的 LinkedHashMap。
	 */
	public static <K extends Comparable<K>, V> LinkedHashMap<K, V> descKey(Map<K, V> map) {
		return sort(map, Map.Entry::getKey, Sort.DESC);
	}

	/**
	 * 业务作用：按值升序输出保序映射。
	 *
	 * @param map 源映射
	 * 返回: 按值升序排列的 LinkedHashMap。
	 */
	public static <K, V extends Comparable<V>> LinkedHashMap<K, V> ascValue(Map<K, V> map) {
		return sort(map, Map.Entry::getValue, Sort.ASC);
	}

	/**
	 * 业务作用：按值降序输出保序映射。
	 *
	 * @param map 源映射
	 * 返回: 按值降序排列的 LinkedHashMap。
	 */
	public static <K, V extends Comparable<V>> LinkedHashMap<K, V> descValue(Map<K, V> map) {
		return sort(map, Map.Entry::getValue, Sort.DESC);
	}

	// ==================== 嵌套取值 (dot-path) ====================

	/**
	 * 业务作用：按点号路径从嵌套映射中逐层取值，例如 user.address.city 依次下钻三层。
	 * 任一层缺失或不是映射即中止，避免调用方写多层判空。
	 *
	 * @param map 源映射
	 * @param path 以点号分隔的嵌套路径
	 * 返回: 路径末端的值；任一层缺失时返回 null。
	 */
	@SuppressWarnings("unchecked")
	public static Object getByPath(Map<String, ?> map, String path) {
		if (isEmpty(map) || StringUtils.isBlank(path)) return null;
		String[] keys = path.split("\\.");
		Object current = map;
		for (String key : keys) {
			if (!(current instanceof Map)) return null;
			current = ((Map<String, ?>) current).get(key);
			if (current == null) return null;
		}
		return current;
	}

	/**
	 * 业务作用：按点号路径取值并转成 String。
	 *
	 * @param map 源映射
	 * @param path 以点号分隔的嵌套路径
	 * 返回: 路径末端的字符串；路径不存在时返回 null。
	 */
	public static String getStringByPath(Map<String, ?> map, String path) {
		Object val = getByPath(map, path);
		return val == null ? null : ObjMprUtils.toString(val);
	}

	/**
	 * 业务作用：按点号路径取值并转成 Long。
	 *
	 * @param map 源映射
	 * @param path 以点号分隔的嵌套路径
	 * 返回: 路径末端的 Long；路径不存在或无法转换时返回 null。
	 */
	public static Long getLongByPath(Map<String, ?> map, String path) {
		Object val = getByPath(map, path);
        return switch (val) {
            case null -> null;
            case Number n -> n.longValue();
            case String s -> Long.parseLong(s);
            default -> null;
        };
    }

	/**
	 * 业务作用：按点号路径取值并转成 Integer。
	 *
	 * @param map 源映射
	 * @param path 以点号分隔的嵌套路径
	 * 返回: 路径末端的 Integer；路径不存在或无法转换时返回 null。
	 */
	public static Integer getIntByPath(Map<String, ?> map, String path) {
		Object val = getByPath(map, path);
        return switch (val) {
            case null -> null;
            case Number n -> n.intValue();
            case String s -> Integer.parseInt(s);
            default -> null;
        };
    }

	// ==================== pick / omit ====================

	/**
	 * 业务作用：只保留指定的键，生成新映射，原映射不变。
	 *
	 * @param map 源映射
	 * @param keys 键集合
	 * 返回: 只含指定键的新映射。
	 */
	@SafeVarargs
	public static <K, V> LinkedHashMap<K, V> pick(Map<K, V> map, K... keys) {
		LinkedHashMap<K, V> result = new LinkedHashMap<>(keys.length);
		for (K key : keys) {
			V val = map.get(key);
			if (val != null) result.put(key, val);
		}
		return result;
	}

	/**
	 * 业务作用：排除指定的键，生成新映射，原映射不变。
	 *
	 * @param map 源映射
	 * @param keys 键集合
	 * 返回: 不含指定键的新映射。
	 */
	@SafeVarargs
	public static <K, V> LinkedHashMap<K, V> omit(Map<K, V> map, K... keys) {
		LinkedHashMap<K, V> result = new LinkedHashMap<>(map);
		for (K key : keys) result.remove(key);
		return result;
	}

	// ==================== transform ====================

	/**
	 * 业务作用：按函数转换全部值，键保持不变。
	 *
	 * @param map 源映射
	 * @param mapper 元素到目标值的映射函数
	 * 返回: 值经过转换的新映射。
	 */
	public static <K, V, R> LinkedHashMap<K, R> mapValues(Map<K, V> map, Function<V, R> mapper) {
		LinkedHashMap<K, R> result = new LinkedHashMap<>(map.size());
		map.forEach((k, v) -> result.put(k, mapper.apply(v)));
		return result;
	}

	/**
	 * 业务作用：按函数转换全部键，值保持不变。键转换后可能冲突，冲突时后者覆盖前者。
	 *
	 * @param map 源映射
	 * @param mapper 元素到目标值的映射函数
	 * 返回: 键经过转换的新映射。
	 */
	public static <K, V, R> LinkedHashMap<R, V> mapKeys(Map<K, V> map, Function<K, R> mapper) {
		LinkedHashMap<R, V> result = new LinkedHashMap<>(map.size());
		map.forEach((k, v) -> result.put(mapper.apply(k), v));
		return result;
	}

	/**
	 * 业务作用：把键值对调。原值重复时对调后会冲突，冲突时后者覆盖前者。
	 *
	 * @param map 源映射
	 * 返回: 键值互换后的新映射。
	 */
	public static <K, V> LinkedHashMap<V, K> invert(Map<K, V> map) {
		LinkedHashMap<V, K> result = new LinkedHashMap<>(map.size());
		map.forEach((k, v) -> result.put(v, k));
		return result;
	}

	// ==================== merge / diff ====================

	/**
	 * 业务作用：按顺序合并多个映射，后者覆盖前者的同键项。
	 *
	 * @param maps 待合并的多个映射
	 * 返回: 合并后的新映射；原映射均不被修改。
	 */
	@SafeVarargs
	public static <K, V> LinkedHashMap<K, V> merge(Map<K, V>... maps) {
		LinkedHashMap<K, V> result = new LinkedHashMap<>();
		for (Map<K, V> m : maps) {
			if (m != null) result.putAll(m);
		}
		return result;
	}

	/**
	 * 业务作用：按条件从映射中移除条目，并把被移除的条目返回，供调用方做后续补偿。
	 *
	 * @param map 源映射
	 * @param predicate 筛选条件，返回 true 才纳入结果
	 * 返回: 被移除的条目组成的映射。
	 */
	public static <K, V> LinkedHashMap<K, V> removeIf(Map<K, V> map, BiPredicate<K, V> predicate) {
		LinkedHashMap<K, V> removed = new LinkedHashMap<>();
		map.entrySet().removeIf(e -> {
			if (predicate.test(e.getKey(), e.getValue())) {
				removed.put(e.getKey(), e.getValue());
				return true;
			}
			return false;
		});
		return removed;
	}

	/**
	 * 业务作用：比较两个映射，找出后者相对前者的新增项与变更项。
	 *
	 * @param first 基准映射
	 * @param second 待比较的映射
	 * 返回: 后者中与前者不同的条目组成的映射。
	 */
	public static <K, V> LinkedHashMap<K, V> diff(Map<K, V> first, Map<K, V> second) {
		LinkedHashMap<K, V> result = new LinkedHashMap<>();
		second.forEach((k, v) -> {
			V v1 = first.get(k);
			if (!Objects.equals(v, v1)) result.put(k, v);
		});
		return result;
	}

	// ==================== 便捷构建 ====================

	/**
	 * 业务作用：开始链式构建映射，供以表达式形式一次性写出字面量映射。
	 *
	 * @param key 键
	 * @param value 值
	 * 返回: 构建器实例。
	 */
	public static <V> MapBuilder<V> builder(String key, V value) {
		return new MapBuilder<V>().put(key, value);
	}

	public static class MapBuilder<V> {
		private final LinkedHashMap<String, V> map = new LinkedHashMap<>();

		/**
		 * 业务作用：向构建中的映射写入一项。
		 *
		 * @param key 键
		 * @param value 值
		 * 返回: 构建器本身，供链式调用。
		 */
		public MapBuilder<V> put(String key, V value) {
			this.map.put(key, value);
			return this;
		}

		/**
		 * 业务作用：条件成立时才写入一项，用于按开关拼装映射而不打断链式调用。
		 *
		 * @param condition 见上述说明
		 * @param key 键
		 * @param value 值
		 * 返回: 构建器本身，供链式调用。
		 */
		public MapBuilder<V> putIf(boolean condition, String key, V value) {
			if (condition) this.map.put(key, value);
			return this;
		}

		/**
		 * 业务作用：值非 null 时才写入一项，避免把 null 混入结果映射。
		 *
		 * @param key 键
		 * @param value 值
		 * 返回: 构建器本身，供链式调用。
		 */
		public MapBuilder<V> putIfNotNull(String key, V value) {
			if (value != null) this.map.put(key, value);
			return this;
		}

		/**
		 * 业务作用：结束链式构建并产出映射。
		 *
		 * 参数说明: 无。
		 * 返回: 构建完成的映射。
		 */
		public LinkedHashMap<String, V> build() {
			return this.map;
		}
	}

}
