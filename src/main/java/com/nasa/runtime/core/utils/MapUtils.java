package com.nasa.runtime.core.utils;

import com.google.common.util.concurrent.AtomicDouble;
import com.nasa.runtime.core.enums.Sort;
import com.nasa.runtime.core.exception.JsonException;
import com.nasa.runtime.core.function.FunctionUtils;
import com.nasa.runtime.core.function.SerFunction;
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
	 * 判断map为空
	 */
	public static boolean isEmpty(Map<?, ?> map) {
		return Objects.isNull(map) || map.isEmpty();
	}

	/**
	 * 判断map不为空
	 */
	public static boolean isNotEmpty(Map<?, ?> map) {
		return !isEmpty(map);
	}

	/**
	 * 对象转LinkedHashMap，通过getter取值，值为null时忽略
	 * @param obj 源对象
	 */
	@SafeVarargs
	public static <T, R> LinkedHashMap<String, Object> toLinkedMap(T obj, SerFunction<T, R>... getters) {
		return (LinkedHashMap<String, Object>) toMap(obj, new LinkedHashMap<>(), getters);
	}

	/**
	 * 对象转HashMap，通过getter取值，值为null时忽略
	 * @param obj 源对象
	 */
	@SafeVarargs
	public static <T, R> HashMap<String, Object> toHashMap(T obj, SerFunction<T, R>... getters) {
		return (HashMap<String, Object>) toMap(obj, new HashMap<>(), getters);
	}

	/**
	 * 对象转Map，通过getter取值，值为null时忽略
	 * @param obj 源对象
	 * @param map 目标map对象
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
	 * 取源map中指定的key到LinkedHashMap
	 * @param sourceMap 源map
	 * @param keys 指定的key
	 * @param <T> map的value的泛型
	 */
	public static <T> Map<String, T> toLinkedMap(Map<String, T> sourceMap, String... keys) {
		return toMap(sourceMap, new LinkedHashMap<>(), keys);
	}

	/**
	 * 取源map中指定的key到HashMap
	 * @param sourceMap 源map
	 * @param keys 指定的key
	 * @param <T> map的value的泛型
	 */
	public static <T> Map<String, T> toHashMap(Map<String, T> sourceMap, String... keys) {
		return toMap(sourceMap, new HashMap<>(), keys);
	}

	/**
	 * 取源map中指定的key到目标map
	 * @param sourceMap 源map
	 * @param targetMap 目标map
	 * @param keys 指定的key
	 * @param <T> map的value的泛型
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
	 * 将map转为指定对象，通过setter赋值，值为null时忽略
	 * @param map 源map对象
	 * @param clazz 目标对象的Class
	 * @param <T> 目标对象的泛型
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
	 * 将map转为指定对象，通过setter赋值，值为null时忽略
	 * @param map 源map对象
	 * @param obj 目标对象
	 * @param <T> 目标对象的泛型
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
	 * 将map转换为ConcurrentMap
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
	 * 将数组转为HashMap
	 * @param kvs 数组：k1, v1, k2, v2, ..., kn, vn
	 * @param <K> key的泛型
	 * @param <V> value的泛型
	 */
	public static <K, V> HashMap<K, V> toHashMap(Object... kvs) {
		return toMap((Supplier<HashMap<K,V>>) HashMap::new, kvs);
	}

	/**
	 * 将数组转为LinkedHashMap
	 * @param kvs 数组：k1, v1, k2, v2, ..., kn, vn
	 * @param <K> key的泛型
	 * @param <V> value的泛型
	 */
	public static <K, V> LinkedHashMap<K, V> toLinkedMap(Object... kvs) {
		return toMap((Supplier<LinkedHashMap<K,V>>) LinkedHashMap::new, kvs);
	}

	/**
	 * 将数组转为Map
	 * @param supplier 生成Map实例函数
	 * @param kvs 数组：k1, v1, k2, v2, ..., kn, vn
	 * @param <K> key的泛型
	 * @param <V> value的泛型
	 * @param <R> 生产的Map的泛型
	 */
	public static <K, V, R extends Map<K, V>> R toMap(Supplier<R> supplier, Object... kvs) {
		return toMap(supplier.get(), kvs);
	}

	/**
	 * 将数组转为Map
	 * @param kvs 数组：k1, v1, k2, v2, ..., kn, vn
	 * @param <K> key的泛型
	 * @param <V> value的泛型
	 * @param <R> 生产的Map的泛型
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
	 * 构建一个不能被修改的map
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
	 * 替map删除key，null默认返回null
     */
	public static <V> V remove(Map map, Object key) {
		return map == null ? null : (V) map.remove(key);
	}

    /**
	 * 替map执行forEach，忽略null
     */
	public static <K, V> void forEach(Map<K, V> map, BiConsumer<? super K, ? super V> action) {
		if (map != null && action != null) map.forEach(action);
	}

	/**
	 * 获取map中的值
	 */
	public static <K, V> V getObject(Map map, K key) {
		return Objects.isNull(map) ? null : (V) map.get(key);
	}

	/**
	 * 获取map中的值
	 */
	public static <K, V, E> E getObjectMapper(Map map, K key, Function<V, E> mapper) {
		return mapper.apply(getObject(map, key));
	}

	/**
	 * 获取map中的值
	 */
	public static <K> String getString(Map map, K key) {
		Object obj = getObject(map, key);
		return Objects.isNull(obj) ? null : ObjMprUtils.toString(obj);
	}

	/**
	 * 获取map中的值
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
	 * 获取map中的值
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
	 * 获取map中的值
	 */
	public static <K> Long getLong(Map map, K key) {
		Number number = getNumber(map, key);
		return Objects.isNull(number) ? null : number.longValue();
	}

	/**
	 * 获取map中的值
	 */
	public static <K> Integer getInteger(Map map, K key) {
		Number number = getNumber(map, key);
		return Objects.isNull(number) ? null : number.intValue();
	}

	/**
	 * 获取map中的值
	 */
	public static <K> Byte getByte(Map map, K key) {
		Number number = getNumber(map, key);
		return Objects.isNull(number) ? null : number.byteValue();
	}

	/**
	 * 获取map中的值
	 */
	public static <K> Short getShort(Map map, K key) {
		Number number = getNumber(map, key);
		return Objects.isNull(number) ? null : number.shortValue();
	}

	/**
	 * 获取map中的值
	 */
	public static <K> Double getDouble(Map map, K key) {
		Number number = getNumber(map, key);
		return Objects.isNull(number) ? null : number.doubleValue();
	}

	/**
	 * 获取map中的值
	 */
	public static <K> Float getFloat(Map map, K key) {
		Number number = getNumber(map, key);
		return Objects.isNull(number) ? null : number.floatValue();
	}

	/**
	 * 获取map中的值
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
	 * 获取map中的值
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
	 * 获取map中的值
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
	 * 获取map中的值
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
	 * 获取map中的值
	 */
	public static <K> AtomicDouble getAtomicDouble(Map map, K key) {
		Object obj = getObject(map, key);
		if (Objects.isNull(obj)) {
			return null;
		}
		if (obj instanceof AtomicDouble) {
			return (AtomicDouble) obj;
		}
		if (obj instanceof Number num) {
			return new AtomicDouble(num.doubleValue());
		}
		try {
			return obj instanceof String os ? new AtomicDouble(Double.parseDouble(os)) : null;
		} catch (Exception e) {
			throw new JsonException(e.getMessage(), e);
		}
	}

	/**
	 * 获取map中的值
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
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K, V> V getObject(Map map, K key, V dftVal) {
		V obj = getObject(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> String getString(Map map, K key, String dftVal) {
		String obj = getString(map, key);
		return StringUtils.isBlank(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Boolean getBoolean(Map map, K key, boolean dftVal) {
		Boolean obj = getBoolean(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Number getNumber(Map map, K key, Number dftVal) {
		Number obj = getNumber(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Long getLong(Map map, K key, long dftVal) {
		Long obj = getLong(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Integer getInteger(Map map, K key, int dftVal) {
		Integer obj = getInteger(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Byte getByte(Map map, K key, byte dftVal) {
		Byte obj = getByte(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Short getShort(Map map, K key, short dftVal) {
		Short obj = getShort(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Double getDouble(Map map, K key, double dftVal) {
		Double obj = getDouble(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Float getFloat(Map map, K key, float dftVal) {
		Float obj = getFloat(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值
	 */
	public static <K> BigDecimal getBigDecimal(Map map, K key, BigDecimal dftVal) {
		BigDecimal obj = getBigDecimal(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值
	 */
	public static <K> BigInteger getBigInteger(Map map, K key, BigInteger dftVal) {
		BigInteger obj = getBigInteger(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值
	 */
	public static <K> AtomicInteger getAtomicInteger(Map map, K key, AtomicInteger dftVal) {
		AtomicInteger obj = getAtomicInteger(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值
	 */
	public static <K> AtomicLong getAtomicLong(Map map, K key, AtomicLong dftVal) {
		AtomicLong obj = getAtomicLong(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值
	 */
	public static <K> AtomicDouble getAtomicDouble(Map map, K key, AtomicDouble dftVal) {
		AtomicDouble obj = getAtomicDouble(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 获取map中的值
	 */
	public static <K> AtomicBoolean getAtomicBoolean(Map map, K key, AtomicBoolean dftVal) {
		AtomicBoolean obj = getAtomicBoolean(map, key);
		return Objects.isNull(obj) ? dftVal : obj;
	}

	/**
	 * 判断map中key是否是true
	 */
	public static <K> boolean isTrue(Map map, K key, boolean dft) {
		return getBoolean(map, key, dft);
	}

	/**
	 * 判断map中key是否是false
	 */
	public static <K> boolean isFalse(Map map, K key, boolean dft) {
		Boolean b = getBoolean(map, key);
		return Objects.isNull(b) ? dft : !b;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K, V> V getObject(Map map, K key, Function<K, V> getter) {
		V obj = getObject(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> String getString(Map map, K key, Function<K, String> getter) {
		String obj = getString(map, key);
		return StringUtils.isBlank(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Boolean getBoolean(Map map, K key, Function<K, Boolean> getter) {
		Boolean obj = getBoolean(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Number getNumber(Map map, K key, Function<K, Number> getter) {
		Number obj = getNumber(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Long getLong(Map map, K key, Function<K, Long> getter) {
		Long obj = getLong(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Integer getInteger(Map map, K key, Function<K, Integer> getter) {
		Integer obj = getInteger(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Byte getByte(Map map, K key, Function<K, Byte> getter) {
		Byte obj = getByte(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Short getShort(Map map, K key, Function<K, Short> getter) {
		Short obj = getShort(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Double getDouble(Map map, K key, Function<K, Double> getter) {
		Double obj = getDouble(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值，没有返回指定默认值
	 */
	public static <K> Float getFloat(Map map, K key, Function<K, Float> getter) {
		Float obj = getFloat(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值
	 */
	public static <K> BigDecimal getBigDecimal(Map map, K key, Function<K, BigDecimal> getter) {
		BigDecimal obj = getBigDecimal(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值
	 */
	public static <K> BigInteger getBigInteger(Map map, K key, Function<K, BigInteger> getter) {
		BigInteger obj = getBigInteger(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值
	 */
	public static <K> AtomicInteger getAtomicInteger(Map map, K key, Function<K, AtomicInteger> getter) {
		AtomicInteger obj = getAtomicInteger(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值
	 */
	public static <K> AtomicLong getAtomicLong(Map map, K key, Function<K, AtomicLong> getter) {
		AtomicLong obj = getAtomicLong(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值
	 */
	public static <K> AtomicDouble getAtomicDouble(Map map, K key, Function<K, AtomicDouble> getter) {
		AtomicDouble obj = getAtomicDouble(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 获取map中的值
	 */
	public static <K> AtomicBoolean getAtomicBoolean(Map map, K key, Function<K, AtomicBoolean> getter) {
		AtomicBoolean obj = getAtomicBoolean(map, key);
		return Objects.isNull(obj) ? getter.apply(key) : obj;
	}

	/**
	 * 条件查询
	 */
	public static <K, V> LinkedHashMap<K, V> getPredicate(Map<K, V> map, BiPredicate<K, V> predicate) {
		LinkedHashMap<K, V> rm = new LinkedHashMap<>();
		map.forEach((k, v) -> {
			if (predicate.test(k, v)) rm.put(k, v);
		});
		return rm;
	}

	/**
	 * 条件查询
	 */
	public static <K, V> ArrayList<V> getValuePredicate(Map<K, V> map, BiPredicate<K, V> predicate) {
		ArrayList<V> list = new ArrayList<>();
		map.forEach((k, v) -> {
			if (predicate.test(k, v)) list.add(v);
		});
		return list;
	}

	/**
	 * 条件查询，返回第一次匹配成功的结果
	 */
	public static <K, V> V getValuePredicateFirst(Map<K, V> map, BiPredicate<K, V> predicate) {
		for (Map.Entry<K, V> entry : map.entrySet()) {
			if (predicate.test(entry.getKey(), entry.getValue())) return entry.getValue();
		}
		return null;
	}

	/**
	 * 条件查询，返回第一次匹配成功的结果
	 */
	public static <K, V> V getValuePredicateFirst(Map<K, V> map, BiPredicate<K, V> predicate, V dft) {
		V v = getValuePredicateFirst(map, predicate);
		return Objects.isNull(v) ? dft : v;
	}

	/**
	 * 排序
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
	 * 按key排升序
	 */
	public static <K extends Comparable<K>, V> LinkedHashMap<K, V> ascKey(Map<K, V> map) {
		return sort(map, Map.Entry::getKey, Sort.ASC);
	}

	/**
	 * 按key排降序
	 */
	public static <K extends Comparable<K>, V> LinkedHashMap<K, V> descKey(Map<K, V> map) {
		return sort(map, Map.Entry::getKey, Sort.DESC);
	}

	/**
	 * 按value排升序
	 */
	public static <K, V extends Comparable<V>> LinkedHashMap<K, V> ascValue(Map<K, V> map) {
		return sort(map, Map.Entry::getValue, Sort.ASC);
	}

	/**
	 * 按value排降序
	 */
	public static <K, V extends Comparable<V>> LinkedHashMap<K, V> descValue(Map<K, V> map) {
		return sort(map, Map.Entry::getValue, Sort.DESC);
	}

	// ==================== 嵌套取值 (dot-path) ====================

	/**
	 * 按点号路径从嵌套 Map 中取值: "user.address.city" → map.get("user").get("address").get("city")
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
	 * 按点号路径取 String
	 */
	public static String getStringByPath(Map<String, ?> map, String path) {
		Object val = getByPath(map, path);
		return val == null ? null : ObjMprUtils.toString(val);
	}

	/**
	 * 按点号路径取 Long
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
	 * 按点号路径取 Integer
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
	 * 选取指定 key (类似 lodash.pick)
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
	 * 排除指定 key (类似 lodash.omit)
	 */
	@SafeVarargs
	public static <K, V> LinkedHashMap<K, V> omit(Map<K, V> map, K... keys) {
		LinkedHashMap<K, V> result = new LinkedHashMap<>(map);
		for (K key : keys) result.remove(key);
		return result;
	}

	// ==================== transform ====================

	/**
	 * 转换所有 value, key 不变
	 */
	public static <K, V, R> LinkedHashMap<K, R> mapValues(Map<K, V> map, Function<V, R> mapper) {
		LinkedHashMap<K, R> result = new LinkedHashMap<>(map.size());
		map.forEach((k, v) -> result.put(k, mapper.apply(v)));
		return result;
	}

	/**
	 * 转换所有 key, value 不变
	 */
	public static <K, V, R> LinkedHashMap<R, V> mapKeys(Map<K, V> map, Function<K, R> mapper) {
		LinkedHashMap<R, V> result = new LinkedHashMap<>(map.size());
		map.forEach((k, v) -> result.put(mapper.apply(k), v));
		return result;
	}

	/**
	 * key-value 互换
	 */
	public static <K, V> LinkedHashMap<V, K> invert(Map<K, V> map) {
		LinkedHashMap<V, K> result = new LinkedHashMap<>(map.size());
		map.forEach((k, v) -> result.put(v, k));
		return result;
	}

	// ==================== merge / diff ====================

	/**
	 * 合并多个 Map, 后者覆盖前者
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
	 * 按条件移除, 返回被移除的 entries
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
	 * 比较两个 Map 的差异, 返回 second 中与 first 不同的 entries (新增 + 变更)
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
	 * 链式构建 Map: MapUtils.builder("k1", v1).put("k2", v2).build()
	 */
	public static <V> MapBuilder<V> builder(String key, V value) {
		return new MapBuilder<V>().put(key, value);
	}

	public static class MapBuilder<V> {
		private final LinkedHashMap<String, V> map = new LinkedHashMap<>();

		public MapBuilder<V> put(String key, V value) {
			this.map.put(key, value);
			return this;
		}

		public MapBuilder<V> putIf(boolean condition, String key, V value) {
			if (condition) this.map.put(key, value);
			return this;
		}

		public MapBuilder<V> putIfNotNull(String key, V value) {
			if (value != null) this.map.put(key, value);
			return this;
		}

		public LinkedHashMap<String, V> build() {
			return this.map;
		}
	}

}
