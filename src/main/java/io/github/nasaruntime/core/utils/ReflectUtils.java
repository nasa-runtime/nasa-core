package io.github.nasaruntime.core.utils;

import io.github.nasaruntime.core.enums.AndOr;
import io.github.nasaruntime.core.enums.Reflect;
import io.github.nasaruntime.core.exception.ReflectException;
import io.github.nasaruntime.core.function.BooleanFunction;
import io.github.nasaruntime.core.function.FunctionUtils;
import io.github.nasaruntime.core.function.SerFunction;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Nasa
 * 反射工具
 */
@SuppressWarnings("unused")
public abstract class ReflectUtils {

	// 反射结果缓存: 避免每次调用都遍历类层次
	private static final ConcurrentHashMap<Class<?>, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Class<?>, List<Method>> GETTER_CACHE = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Class<?>, List<Method>> SETTER_CACHE = new ConcurrentHashMap<>();


	/* waitMillis 方法的锁对象 */
	private static final ReentrantLock waitMillisLock = new ReentrantLock();
	private static final Condition waitMillisCondition = waitMillisLock.newCondition();

	public static final String get = "get";
	public static final String is = "is";
	public static final String set = "set";
	public static final String GetClass = "getClass";
	/**
	 * 业务作用：列出类及其全部父类（不含 Object）声明的普通字段，供属性拷贝与序列化按字段遍历。
	 *
	 * @param clazz 目标类型
	 * 返回: 字段列表；子类字段在前，父类字段在后。
	 */
	public static List<Field> allField(Class<?> clazz) {
		return FIELD_CACHE.computeIfAbsent(clazz, c -> allField(c, Reflect.IsOrdinary));
	}


    /**
     * 业务作用：列出类及其全部父类（不含 Object）声明的普通字段，供属性拷贝与序列化按字段遍历。
     *
     * @param clazz 目标类型
     * @param conditions 见上述说明
     * 返回: 字段列表；子类字段在前，父类字段在后。
     */
	@SafeVarargs
    public static List<Field> allField(Class<?> clazz, Function<Member, Boolean>... conditions) {
		return allField(clazz, AndOr.And, conditions);
	}


	/**
	 * 业务作用：列出类及其全部父类（不含 Object）声明的普通字段，供属性拷贝与序列化按字段遍历。
	 *
	 * @param clazz 目标类型
	 * @param conditions 见上述说明
	 * 返回: 字段列表；子类字段在前，父类字段在后。
	 */
	public static List<Field> allField(Class<?> clazz, Reflect... conditions) {
		return allField(clazz, AndOr.And, ColUtils.toArray(Function.class, conditions, r -> true, Reflect::getApplier));
	}


    /**
     * 业务作用：列出类及其全部父类（不含 Object）声明的普通字段，供属性拷贝与序列化按字段遍历。
     *
     * @param clazz 目标类型
     * @param andOr 见上述说明
     * @param conditions 见上述说明
     * 返回: 字段列表；子类字段在前，父类字段在后。
     */
	@SafeVarargs
    public static List<Field> allField(Class<?> clazz, AndOr andOr, Function<Member, Boolean>... conditions) {
		List<Field> result = new ArrayList<>();
		while (clazz != Object.class) {
			for (Field field : clazz.getDeclaredFields()) {
				if (matchConditions(field, andOr, conditions)) result.add(field);
			}
			clazz = clazz.getSuperclass();
		}
		return result;
	}


	/**
	 * 业务作用：列出全部 getter，包含 boolean 属性的 is 方法。结果带缓存，适合热路径反复调用。
	 *
	 * @param clazz 目标类型
	 * 返回: getter 方法列表。
	 */
	public static List<Method> allGetter(Class<?> clazz) {
		return GETTER_CACHE.computeIfAbsent(clazz, c -> allMethod(c, Reflect.IsGetter.getApplier()));
	}


	/**
	 * 业务作用：列出全部 setter。结果带缓存，适合热路径反复调用。
	 *
	 * @param clazz 目标类型
	 * 返回: setter 方法列表。
	 */
	public static List<Method> allSetter(Class<?> clazz) {
		return SETTER_CACHE.computeIfAbsent(clazz, c -> allMethod(c, Reflect.IsSetter.getApplier()));
	}


	/**
	 * 业务作用：列出类及其全部父类（不含 Object）声明的方法。
	 *
	 * @param clazz 目标类型
	 * 返回: 方法列表。
	 */
	public static List<Method> allMethod(Class<?> clazz) {
		return allMethod(clazz, AndOr.And, (Function<Member, Boolean>[]) null);
	}


    /**
     * 业务作用：列出类及其全部父类（不含 Object）声明的方法。
     *
     * @param clazz 目标类型
     * @param conditions 见上述说明
     * 返回: 方法列表。
     */
	@SafeVarargs
    public static List<Method> allMethod(Class<?> clazz, Function<Member, Boolean>... conditions) {
		return allMethod(clazz, AndOr.And, conditions);
	}


	/**
	 * 业务作用：列出类及其全部父类（不含 Object）声明的方法。
	 *
	 * @param clazz 目标类型
	 * @param conditions 见上述说明
	 * 返回: 方法列表。
	 */
	public static List<Method> allMethod(Class<?> clazz, Reflect... conditions) {
		return allMethod(clazz, AndOr.And, ColUtils.toArray(Function.class, conditions, r -> true, Reflect::getApplier));
	}


    /**
     * 业务作用：列出类及其全部父类（不含 Object）声明的方法。
     *
     * @param clazz 目标类型
     * @param andOr 见上述说明
     * @param conditions 见上述说明
     * 返回: 方法列表。
     */
	@SafeVarargs
    public static List<Method> allMethod(Class<?> clazz, AndOr andOr, Function<Member, Boolean>... conditions) {
		if (andOr == null) andOr = AndOr.And;
		List<Method> result = new ArrayList<>();
		while (clazz != Object.class) {
			for (Method method : clazz.getDeclaredMethods()) {
				if (matchConditions(method, andOr, conditions)) result.add(method);
			}
			clazz = clazz.getSuperclass();
		}
		return result;
	}

	/**
	 * 业务作用：公共条件匹配: And 模式全部满足, Or 模式满足一个
	 *
	 * @param member 见上述说明
	 * @param andOr 见上述说明
	 * @param conditions 见上述说明
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	@SafeVarargs
	private static <M extends Member> boolean matchConditions(M member, AndOr andOr, Function<Member, Boolean>... conditions) {
		if (ColUtils.isEmpty(conditions)) return true;
		for (Function<Member, Boolean> cond : conditions) {
			if (andOr == AndOr.And) {
				if (!cond.apply(member)) return false;
			} else {
				if (cond.apply(member)) return true;
			}
		}
		return andOr == AndOr.And;
	}


	/**
	 * 业务作用：反射调用实例方法。
	 *
	 * @param obj 目标对象
	 * @param methodName 方法名
	 * @param args 调用实参
	 * 返回: 方法返回值；方法不存在或调用抛错时抛出 ReflectException。
	 */
	public static Object invoke(Object obj, String methodName, Object... args) {
		try {
			if (args == null || args.length == 0) {
				return obj.getClass().getMethod(methodName).invoke(obj);
			}
			Class<?>[] paramTypes = new Class<?>[args.length];
			for (int i = 0; i < args.length; i++) {
				paramTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
			}
			return obj.getClass().getMethod(methodName, paramTypes).invoke(obj, args);
		} catch (Throwable e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 反射执行方法
	 * @param obj 源对象
	 * @param method 方法
	 * @param args 参数
	 */
	@SuppressWarnings("UnusedReturnValue") // 抑制方法返回值没有被引用的警告
	/**
	 * 业务作用：反射调用实例方法。
	 *
	 * @param obj 目标对象
	 * @param method 方法
	 * @param args 调用实参
	 * 返回: 方法返回值；方法不存在或调用抛错时抛出 ReflectException。
	 */
	public static Object invoke(Object obj, Method method, Object... args) {
		try {
			return method.invoke(obj, args);
		} catch (Throwable e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 业务作用：反射读取实例字段值，绕过可见性限制。
	 *
	 * @param obj 目标对象
	 * @param fieldName 字段名
	 * 返回: 字段值；字段不存在时抛出 ReflectException。
	 */
	public static Object fieldGet(Object obj, String fieldName) {
		return fieldGet(obj, obj.getClass(), fieldName);
	}

	/**
	 * 业务作用：反射读取实例字段值，绕过可见性限制。
	 *
	 * @param obj 目标对象
	 * @param clazz 目标类型
	 * @param fieldName 字段名
	 * 返回: 字段值；字段不存在时抛出 ReflectException。
	 */
	public static Object fieldGet(Object obj, Class<?> clazz, String fieldName) {
		try {
			return fieldGet(obj, clazz.getDeclaredField(fieldName));
		} catch (NoSuchFieldException e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 业务作用：反射读取实例字段值，绕过可见性限制。
	 *
	 * @param obj 目标对象
	 * @param field 字段
	 * 返回: 字段值；字段不存在时抛出 ReflectException。
	 */
	public static Object fieldGet(Object obj, Field field) {
		try {
			field.setAccessible(true);
			return field.get(obj);
		} catch (Throwable e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 反射获取最近父类属性值
	 * @param obj 源对象
	 * @param fieldName 属性名称
	 */
	@SuppressWarnings("ConstantConditions") // 抑制有可能产生空指针警告
	/**
	 * 业务作用：从最近的父类开始查找同名字段并读取其值，用于子类遮蔽父类字段时取父类的那一份。
	 *
	 * @param obj 目标对象
	 * @param fieldName 字段名
	 * 返回: 找到的第一个同名父类字段的值。
	 */
	public static Object parentFirstFieldGet(Object obj, String fieldName) {
		List<Field> fields = allFieldInParent(obj.getClass().getSuperclass(), fieldName);
		if (ColUtils.isEmpty(fields)) {
			throw new ReflectException("{} 的父类中没有 {} 属性", obj.getClass().getName(), fieldName);
		}
		return fieldGet(obj, fields.getFirst());
	}


	/**
	 * 反射获取值不为null的最近父类属性值
	 * @param obj 源对象
	 * @param fieldName 属性名称
	 */
	@SuppressWarnings("ConstantConditions") // 抑制有可能产生空指针警告
	/**
	 * 业务作用：从最近的父类开始查找同名字段，返回第一个非 null 的值，用于多层继承中取最近一层已赋值的字段。
	 *
	 * @param obj 目标对象
	 * @param fieldName 字段名
	 * 返回: 首个非 null 的父类字段值；全部为 null 时返回 null。
	 */
	public static Object parentFirstFieldGetNonnull(Object obj, String fieldName) {
		List<Field> fields = allFieldInParent(obj.getClass().getSuperclass(), fieldName);
		if (ColUtils.isEmpty(fields)) {
			throw new ReflectException("{} 的父类中没有 {} 属性", obj.getClass().getName(), fieldName);
		}
		for (Field field : fields) {
			Object o = fieldGet(obj, field);
			if (Objects.nonNull(o)) {
				return o;
			}
		}
		return null;
	}


	/**
	 * 业务作用：反射设置属性值
	 *
	 * @param obj 源对象
	 * @param fieldName 属性名称
	 * @param value 值
	 * 返回: 无返回值。
	 */
	public static void fieldSet(Object obj, String fieldName, Object value) {
		try {
			fieldSet(obj, obj.getClass().getDeclaredField(fieldName), value);
		} catch (NoSuchFieldException e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 业务作用：反射设置属性值
	 *
	 * @param obj 源对象
	 * @param field 属性
	 * @param value 值
	 * 返回: 无返回值。
	 */
	public static void fieldSet(Object obj, Field field, Object value) {
		if (isFinal(field)) {
			throw new ReflectException("The {} is final in the {}, cannot be set."
					, field.getName(), obj.getClass().getName());
		}
		try {
			field.setAccessible(true);
			field.set(obj, value);
		} catch (Throwable e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 反射设置父类属性值
	 * @param obj 源对象
	 * @param fieldName 属性名称
	 * @param value 值
	 */
	@SuppressWarnings("ConstantConditions") // 抑制有可能产生空指针警告
	/**
	 * 业务作用：反射写入父类中的同名字段。
	 *
	 * @param obj 目标对象
	 * @param fieldName 字段名
	 * @param value 值
	 * 返回: 无返回值。
	 */
	public static void parentFieldSet(Object obj, String fieldName, Object value) {
		List<Field> fields = allFieldInParent(obj.getClass().getSuperclass(), fieldName);
		if (ColUtils.isEmpty(fields)) {
			return;
		}
		for (Field field : fields) {
			fieldSet(obj, field, value);
		}
	}


	/**
	 * 反射设置父类属性值，非null的就忽略
	 * @param obj 源对象
	 * @param fieldName 属性名称
	 * @param value 值
	 */
	@SuppressWarnings("ConstantConditions") // 抑制有可能产生空指针警告
	/**
	 * 业务作用：仅在父类同名字段当前为 null 时才写入，避免覆盖已有值。
	 *
	 * @param obj 目标对象
	 * @param fieldName 字段名
	 * @param value 值
	 * 返回: 无返回值。
	 */
	public static void parentFieldSetIfNull(Object obj, String fieldName, Object value) {
		List<Field> fields = allFieldInParent(obj.getClass().getSuperclass(), fieldName);
		if (ColUtils.isEmpty(fields)) {
			return;
		}
		for (Field field : fields) {
			Object val = fieldGet(obj, field);
			if (Objects.nonNull(val)) {
				continue;
			}
			fieldSet(obj, field, value);
		}
	}


	/**
	 * 业务作用：列出各级父类中与给定字段同名的字段，用于处理子类遮蔽父类字段的场景。
	 *
	 * @param clazz 目标类型
	 * @param fieldName 字段名
	 * 返回: 同名字段列表，按继承层级由近及远。
	 */
	public static List<Field> allFieldInParent(Class<?> clazz, String fieldName) {
		if (clazz == Object.class) {
			return null;
		}
		List<Field> list = new ArrayList<>();
		allFieldInParent(list, clazz, fieldName);
		return list;
	}


	/**
	 * 业务作用：获取所有父类同名的属性，排除Object类
	 *
	 * @param list 集合
	 * @param clazz 类
	 * @param fieldName 属性名
	 * 返回: 无返回值。
	 */
	@SuppressWarnings("rawtypes")
	public static void allFieldInParent(List<Field> list, Class clazz, String fieldName) {
		if (clazz == Object.class) {
			return;
		}
		try {
			Field field = clazz.getDeclaredField(fieldName);
			list.add(field);
			allFieldInParent(list, clazz.getSuperclass(), fieldName);
		} catch (NoSuchFieldException e) {
			// 父类没有属性，继续向上找
			allFieldInParent(list, clazz.getSuperclass(), fieldName);
		}
	}


	/**
	 * 业务作用：反射读取静态字段值。
	 *
	 * @param clazz 目标类型
	 * @param fieldName 字段名
	 * 返回: 字段值；字段不存在时抛出 ReflectException。
	 */
	public static Object fieldGetStatic(Class<?> clazz, String fieldName) {
		try {
			Field field = clazz.getDeclaredField(fieldName);
			field.setAccessible(true);
			return field.get(null);
		} catch (Throwable e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 业务作用：反射设置静态属性值
	 *
	 * @param clazz 类
	 * @param fieldName 属性名
	 * @param value 值
	 * 返回: 无返回值。
	 */
	public static void fieldSetStatic(Class<?> clazz, String fieldName, Object value) {
		try {
			Field field = clazz.getDeclaredField(fieldName);
			if (isFinal(field)) {
				throw new ReflectException("The {} is final in the {}, cannot be set."
						, field.getName(), clazz.getName());
			}
			field.setAccessible(true);
			field.set(null, value);
		} catch (Throwable e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 业务作用：反射调用静态方法。
	 *
	 * @param cla 见上述说明
	 * @param sm 见上述说明
	 * 返回: 方法返回值；方法不存在或调用抛错时抛出 ReflectException。
	 */
	@SuppressWarnings("all")
	public static Object invokeStatic(Class<?> cla, String sm) {
		return invokeStatic(cla, sm, null);
	}


	/**
	 * 业务作用：反射调用静态方法。
	 *
	 * @param cla 见上述说明
	 * @param sm 见上述说明
	 * @param classes 见上述说明
	 * @param args 调用实参
	 * 返回: 方法返回值；方法不存在或调用抛错时抛出 ReflectException。
	 */
	@SuppressWarnings("all")
	public static Object invokeStatic(Class<?> cla, String sm, Class<?>[] classes, Object... args) {
		try {
			Method method = cla.getDeclaredMethod(sm, classes);
			method.setAccessible(true);
			return method.invoke(null, args);
		} catch (Throwable e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 业务作用：扫描包下全部类，遇到无法加载的类直接失败。
	 *
	 * @param path 包路径
	 * @param bfs 见上述说明
	 * 返回: 类列表。
	 */
	@SafeVarargs
	public static ArrayList<Class<Object>> allClasses(String path, BooleanFunction<Class<Object>>... bfs) {
		return classesOfType(path, null, bfs);
	}


	/**
	 * 业务作用：扫描包下全部类并跳过无法加载的类，容忍可选依赖缺失。
	 *
	 * @param path 包路径
	 * @param bfs 见上述说明
	 * 返回: 可加载的类列表。
	 */
	@SafeVarargs
	public static ArrayList<Class<Object>> allClassesIfPresent(String path, BooleanFunction<Class<Object>>... bfs) {
		return allClassesIfPresent(path, null, bfs);
	}


	/**
	 * 业务作用：扫描包下全部类并跳过无法加载的类，容忍可选依赖缺失。
	 *
	 * @param path 包路径
	 * @param type 目标类型
	 * @param bfs 见上述说明
	 * 返回: 可加载的类列表。
	 */
	@SafeVarargs
	public static <T> ArrayList<Class<T>> allClassesIfPresent(String path, Class<T> type, BooleanFunction<Class<T>>... bfs) {
		return classesOfType(Thread.currentThread().getContextClassLoader(), path, true, type, bfs);
	}


	/**
	 * 业务作用：扫描包下继承自某父类或实现某接口的类。
	 *
	 * @param path 路径
	 * @param type 目标类型
	 * @param bfs 见上述说明
	 * 返回: 命中的类列表；父类或接口自身不会被收录。
	 */
	@SafeVarargs
	public static <T, E extends T> ArrayList<Class<T>> classesOfType(
			String path, Class<T> type, BooleanFunction<Class<T>>... bfs) {
		return classesOfType(Thread.currentThread().getContextClassLoader(), path, false, type, bfs);
	}


	/**
	 * 业务作用: 扫描指定包中的业务类型，并按父类型和调用方条件过滤可用扩展实现。
	 *
	 * @param classLoader 类加载器
	 * @param path 目录地址：类似 com.example.app
	 * @param type 指定接口或父类的class
	 * @param bfs 条件函数
	 * @return 满足类型与条件约束的类集合；扫描失败且不允许忽略时抛出反射异常
	 */
	@SafeVarargs
	public static <T, E extends T> ArrayList<Class<T>> classesOfType(
			ClassLoader classLoader, String path, Class<T> type, BooleanFunction<Class<T>>... bfs) {
		return classesOfType(classLoader, path, false, type, bfs);
	}


	/**
	 * 业务作用：扫描包下继承自某父类或实现某接口的类。
	 *
	 * @param classLoader 类加载器
	 * @param path 路径
	 * @param isPresent 见上述说明
	 * @param type 目标类型
	 * @param bfs 见上述说明
	 * 返回: 命中的类列表；父类或接口自身不会被收录。
	 */
	@SuppressWarnings({"unchecked"})
	public static <T, E extends T> ArrayList<Class<T>> classesOfType(ClassLoader classLoader
			, String path, boolean isPresent, Class<T> type, BooleanFunction<Class<T>>... bfs) {
		Objects.requireNonNull(classLoader, "classLoader");
		Objects.requireNonNull(path, "path");
		String resourcePath = path.replace('.', '/');
		try {
			Set<String> classNames = new LinkedHashSet<>();
			Enumeration<URL> resources = classLoader.getResources(resourcePath);
			while (resources.hasMoreElements()) {
				collectClassNames(resources.nextElement(), path, resourcePath, classNames);
			}
			ArrayList<Class<T>> list = new ArrayList<>();
			for (String className : classNames) {
				Class<?> clazz;
				try {
					clazz = Class.forName(className, false, classLoader);
					// 丢弃掉缺失导包的类
					ReflectUtils.allMethod(clazz);
				} catch (Throwable e) {
					if (isPresent) {
						continue;
					}
					throw e;
				}
				// 判断是否有指定父类或接口
				if (Objects.nonNull(type) && (clazz == type || !type.isAssignableFrom(clazz))) {
					continue;
				}
				Class<T> cla = (Class<T>) clazz;
				if (ColUtils.isNotEmpty(bfs) && ColUtils.predicate(bfs, booleanFunction -> !booleanFunction.apply(cla))) {
					continue;
				}
				// 非注解、抽象类、接口，可实例化
				list.add(cla);
			}
			return list;
		} catch (Throwable e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 业务作用: 根据类路径资源协议收集包下类名，同时覆盖文件目录和 JAR 两种部署形态。
	 *
	 * @param resource 包资源地址
	 * @param packageName Java 包名
	 * @param resourcePath 包对应的类路径
	 * @param classNames 去重后的类名集合
	 * 返回: 无；发现的类名会加入集合。
	 */
	private static void collectClassNames(
			URL resource, String packageName, String resourcePath, Set<String> classNames)
			throws IOException, URISyntaxException {
		if ("file".equals(resource.getProtocol())) {
			collectDirectoryClassNames(new File(resource.toURI()), packageName, classNames);
			return;
		}
		if (!"jar".equals(resource.getProtocol())) {
			return;
		}
		JarURLConnection connection = (JarURLConnection) resource.openConnection();
		connection.setUseCaches(false);
		try (JarFile jar = connection.getJarFile()) {
			Enumeration<JarEntry> entries = jar.entries();
			String prefix = resourcePath.endsWith("/") ? resourcePath : resourcePath + "/";
			while (entries.hasMoreElements()) {
				String name = entries.nextElement().getName();
				if (name.startsWith(prefix) && name.endsWith(".class")) {
					classNames.add(name.substring(0, name.length() - 6).replace('/', '.'));
				}
			}
		}
	}


	/**
	 * 业务作用: 递归扫描展开目录中的字节码文件，并将相对目录转换为完整类名。
	 *
	 * @param directory 当前包目录
	 * @param packageName 当前 Java 包名
	 * @param classNames 去重后的类名集合
	 * 返回: 无；发现的类名会加入集合。
	 */
	private static void collectDirectoryClassNames(
			File directory, String packageName, Set<String> classNames) {
		File[] files = directory.listFiles(file -> file.isDirectory() || file.getName().endsWith(".class"));
		if (Objects.isNull(files)) {
			return;
		}
		for (File file : files) {
			if (file.isDirectory()) {
				collectDirectoryClassNames(file, packageName + "." + file.getName(), classNames);
			} else {
				classNames.add(packageName + "." + file.getName().substring(0, file.getName().length() - 6));
			}
		}
	}


	/**
	 * 业务作用：扫描包下某父类或接口的实现类，逐个实例化并按类型索引，供以约定优于配置的方式装配可插拔实现。
	 *
	 * @param type 目标类型
	 * @param keyApplier 见上述说明
	 * @param path 包路径
	 * 返回: 类型到实例的映射。
	 */
	@SuppressWarnings("unchecked")
	public static <T, E extends T, K> Map<K, T> beanMapOfType(Class<T> type, Function<E, K> keyApplier, String path) {
		// 将指定包下所有指定注解注解的类的Class放入Map返回
		Map<K, T> map = new LinkedHashMap<>();
		ArrayList<Class<T>> classes = classesOfType(path, type, ReflectUtils::isNotAbstract, ReflectUtils::isNotInterface);
		List<Object> list = ColUtils.toList(classes, ReflectUtils::newInstance);
		ColUtils.ascOrder(list);
		for (Object value : list) {
			map.put(keyApplier.apply((E) value), (T) value);
		}
		return map;
	}


	/**
	 * 创建指定目录下，被指定注解注解的类的实例，并返回map
	 * @param clazz 注解的class
	 * @param keyApplier 生成map的key的函数
	 * @param path 目录地址：类似 com.example.app
	 */
	public static <A extends Annotation, T, E, K>
	/**
	 * 业务作用：扫描包下带有指定注解的类，逐个实例化并索引。
	 *
	 * @param clazz 目标类型
	 * @param keyApplier 见上述说明
	 * @param path 包路径
	 * 返回: 类型到实例的映射。
	 */
	Map<K, T> beanMapOfAnnotation(Class<A> clazz, SerFunction<E, K> keyApplier, String path) {
		return beanMapOfAnnotation(clazz, keyApplier, path, null);
	}


	/**
	 * 创建指定目录下，被指定注解注解的类的实例，并返回map
	 * @param clazz 注解的class
	 * @param keyApplier 生成map的key的函数
	 * @param path 目录地址：类似 com.example.app
	 * @param type 返回Map的value的类型
	 */
	@SuppressWarnings("unchecked")
	public static <A extends Annotation, T, E, K>
	/**
	 * 业务作用：扫描包下带有指定注解的类，逐个实例化并索引。
	 *
	 * @param clazz 目标类型
	 * @param keyApplier 见上述说明
	 * @param path 包路径
	 * @param type 目标类型
	 * 返回: 类型到实例的映射。
	 */
	Map<K, T> beanMapOfAnnotation(Class<A> clazz, SerFunction<E, K> keyApplier, String path, Class<T> type) {
		// key生成函数是否是注解的方法
		boolean keyApplierIsAnnotation = clazz.getName().equals(
				FunctionUtils.serializedLambda(keyApplier)
						.getImplClass()
						.replace(StringUtils.Mark_right_slash, StringUtils.Mark_spot));
		// 将指定包下所有指定注解注解的类的Class放入Map返回
		Map<K, T> map = new LinkedHashMap<>();
		ArrayList<Class<T>> classes = classesOfType(path, type, ReflectUtils::isNotAbstract, ReflectUtils::isNotInterface);
		List<Object> list = ColUtils.toList(classes, ReflectUtils::newInstance);
		ColUtils.ascOrder(list);
		for (Object value : list) {
			if (keyApplierIsAnnotation) {
				// 是注解的方法
				A annotation = value.getClass().getAnnotation(clazz);
				map.put(keyApplier.apply((E) annotation), (T) value);
			} else {
				// 是类的方法、或interface的接口
				map.put(keyApplier.apply((E) value), (T) value);
			}
		}
		return map;
	}


	/**
	 * 业务作用：列出实例中值为 null 的字段名，供参数校验与差异比对。
	 *
	 * @param source 见上述说明
	 * 返回: 值为 null 的字段名集合。
	 */
	public static List<String> fieldIsnull(Object source) {
		try {
			List<String> nullCol = new ArrayList<>();
			List<Field> fields = allField(source.getClass());
			for (Field field : fields) {
				field.setAccessible(true);
				if (Objects.isNull(field.get(source))) {
					nullCol.add(field.getName());
				}
			}
			return nullCol;
		} catch (IllegalAccessException e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 业务作用：列出实例中值不为 null 的字段名，供构造动态更新语句时只带上已赋值字段。
	 *
	 * @param source 见上述说明
	 * 返回: 值非 null 的字段名集合。
	 */
	public static List<String> fieldNonnull(Object source) {
		try {
			List<String> nonnullCol = new ArrayList<>();
			List<Field> fields = allField(source.getClass());
			for (Field field : fields) {
				field.setAccessible(true);
				if (Objects.nonNull(field.get(source))) {
					nonnullCol.add(field.getName());
				}
			}
			return nonnullCol;
		} catch (IllegalAccessException e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 业务作用：由 getter 或 setter 方法名推出属性名，只支持 get、is、set 三种前缀。
	 *
	 * @param getterFunction 见上述说明
	 * 返回: 属性名。
	 */
	public static <T, R> String fieldName(SerFunction<T, R> getterFunction) {
		return FunctionUtils.fieldName(getterFunction);
	}


	/**
	 * 业务作用：由 getter 或 setter 方法名推出属性名，只支持 get、is、set 三种前缀。
	 *
	 * @param getter 见上述说明
	 * 返回: 属性名。
	 */
	public static String fieldName(Method getter) {
		return fieldName(getter.getName());
	}


	/**
	 * 业务作用：由 getter 或 setter 方法名推出属性名，只支持 get、is、set 三种前缀。
	 *
	 * @param getterName 见上述说明
	 * 返回: 属性名。
	 */
	public static String fieldName(String getterName) {
		int offset;
		if (getterName.startsWith(get)) {
			offset = 3;
		} else if (getterName.startsWith(is)) {
			offset = 2;
		} else {
			throw new ReflectException("参数必须是get或者is方法名");
		}
		// 直接操作 char[] 避免 char + String 自动装箱拼接
		char[] chars = getterName.toCharArray();
		char c = chars[offset];
		if (c >= 'A' && c <= 'Z') chars[offset] = (char) (c | 32);
		return new String(chars, offset, chars.length - offset);
	}


	/**
	 * 业务作用：由属性名拼出对应的 setter 方法名。
	 *
	 * @param fieldName 字段名
	 * 返回: setter 方法名。
	 */
	public static String toSetterName(String fieldName) {

		char at = fieldName.charAt(0);
		if (at >= 'a' && at <= 'z') {
			// 小写转大写
			at &= (char) -33;
		}
		return set + at + fieldName.substring(1);
	}


	/**
	 * 业务作用：由属性名拼出对应的 getter 方法名。
	 *
	 * @param fieldName 字段名
	 * 返回: getter 方法名。
	 */
	public static String toGetterName(String fieldName) {

		char at = fieldName.charAt(0);
		if (at >= 'a' && at <= 'z') {
			// 小写转大写
			at &= (char) -33;
		}
		return get + at + fieldName.substring(1);
	}


	/**
	 * 业务作用：由属性名拼出 boolean 属性对应的 is 方法名。
	 *
	 * @param fieldName 字段名
	 * 返回: is 方法名。
	 */
	public static String toIsName(String fieldName) {

		char at = fieldName.charAt(0);
		if (at >= 'a' && at <= 'z') {
			// 小写转大写
			at &= (char) -33;
		}
		return is + at + fieldName.substring(1);
	}


	/**
	 * 业务作用: 判断对象是否为名称带双美元分隔符的生成子类代理。
	 *
	 * @param proxy 代理对象
	 * @return 符合生成子类特征时返回 {@code true}
	 */
	public static boolean isCglibProxy(Object proxy) {
		return proxy != null
				&& proxy.getClass().getName().contains("$$")
				&& proxy.getClass().getSuperclass() != Object.class;
	}


	/**
	 * 业务作用: 判断对象是否不是生成子类代理。
	 *
	 * @param proxy 代理对象
	 * @return 不符合生成子类特征时返回 {@code true}
	 */
	public static boolean isNotCglibProxy(Object proxy) {
		return !isCglibProxy(proxy);
	}


	/**
	 * 业务作用: 使用 JDK 标准代理 API 判断对象是否为接口代理。
	 *
	 * @param proxy 代理对象
	 * @return JDK 接口代理返回 {@code true}
	 */
	public static boolean isJDKProxy(Object proxy) {
		return proxy != null && Proxy.isProxyClass(proxy.getClass());
	}


	/**
	 * 业务作用: 判断对象是否不是 JDK 接口代理。
	 *
	 * @param proxy 代理对象
	 * @return 非 JDK 接口代理返回 {@code true}
	 */
	public static boolean isNotJDKProxy(Object proxy) {
		return !isJDKProxy(proxy);
	}


	/**
	 * 业务作用: 统一识别 JDK 接口代理和生成子类代理。
	 *
	 * @param proxy 代理对象
	 * @return 任一代理特征匹配时返回 {@code true}
	 */
	public static boolean isProxy(Object proxy) {
		return isCglibProxy(proxy) || isJDKProxy(proxy);
	}


	/**
	 * 业务作用: 判断对象是否为普通业务实例。
	 *
	 * @param proxy 代理对象
	 * @return 不包含已知代理特征时返回 {@code true}
	 */
	public static boolean isNotProxy(Object proxy) {
		return !isProxy(proxy);
	}

	/**
	 * 业务作用：是否是 Lambda 表达式生成的实例 (类名含 $$Lambda)
	 *
	 * @param obj 实例对象
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isLambda(Object obj) {
		return obj != null && obj.getClass().getName().contains("$$Lambda");
	}

	/**
	 * 业务作用：是否不是 Lambda 表达式生成的实例
	 *
	 * @param obj 实例对象
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isNotLambda(Object obj) {
		return !isLambda(obj);
	}


	/**
	 * 业务作用: 返回可安全调用的原始引用；不猜测第三方代理内部结构，避免反射破坏封装或触发模块访问错误。
	 *
	 * @param proxy 代理类对象
	 * @return 传入的对象引用
	 */
	public static Object getTarget(Object proxy) {
		return proxy;
	}


	/**
	 * 业务作用: 解析普通对象或生成代理对外代表的业务类型，供注解读取与类型匹配使用。
	 *
	 * @param instance 实例对象
	 * @return 普通对象返回自身类型，JDK 代理返回首个接口，子类代理返回其父类
	 */
	public static Class<?> targetClass(Object instance) {
		Objects.requireNonNull(instance, "instance");
		Class<?> type = instance.getClass();
		if (Proxy.isProxyClass(type) && type.getInterfaces().length > 0) {
			return type.getInterfaces()[0];
		}
		return isCglibProxy(instance) ? type.getSuperclass() : type;
	}


	/**
	 * 业务作用：是否是static属性/方法
	 *
	 * @param member 属性/方法
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isStatic(Member member) {
		return Modifier.isStatic(member.getModifiers());
	}


	/**
	 * 业务作用：是否是static类
	 *
	 * @param clazz 类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isStatic(Class<?> clazz) {
		return Modifier.isStatic(clazz.getModifiers());
	}


	/**
	 * 业务作用：是否是非static属性/方法
	 *
	 * @param member 属性/方法
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isNotStatic(Member member) {
		return !isStatic(member);
	}


	/**
	 * 业务作用：是否是非static类
	 *
	 * @param clazz 类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isNotStatic(Class<?> clazz) {
		return !isStatic(clazz);
	}


	/**
	 * 业务作用：是否是final属性/方法
	 *
	 * @param member 属性/方法
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isFinal(Member member) {
		return Modifier.isFinal(member.getModifiers());
	}


	/**
	 * 业务作用：是否是final类
	 *
	 * @param clazz 类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isFinal(Class<?> clazz) {
		return Modifier.isFinal(clazz.getModifiers());
	}


	/**
	 * 业务作用：是否是非final属性/方法
	 *
	 * @param member 属性/方法
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isNotFinal(Member member) {
		return !isFinal(member);
	}


	/**
	 * 业务作用：是否是非final类
	 *
	 * @param clazz 类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isNotFinal(Class<?> clazz) {
		return !isFinal(clazz);
	}


	/**
	 * 业务作用：是否是public属性/方法
	 *
	 * @param member 属性/方法
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isPublic(Member member) {
		return Modifier.isPublic(member.getModifiers());
	}


	/**
	 * 业务作用：是否是public类
	 *
	 * @param clazz 类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isPublic(Class<?> clazz) {
		return Modifier.isPublic(clazz.getModifiers());
	}


	/**
	 * 业务作用：是否是private属性/方法
	 *
	 * @param member 属性/方法
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isPrivate(Member member) {
		return Modifier.isPrivate(member.getModifiers());
	}


	/**
	 * 业务作用：是否是private类
	 *
	 * @param clazz 类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isPrivate(Class<?> clazz) {
		return Modifier.isPrivate(clazz.getModifiers());
	}


	/**
	 * 业务作用：是否是protected属性/方法
	 *
	 * @param member 属性/方法
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isProtected(Member member) {
		return Modifier.isProtected(member.getModifiers());
	}


	/**
	 * 业务作用：是否是protected类
	 *
	 * @param clazz 类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isProtected(Class<?> clazz) {
		return Modifier.isProtected(clazz.getModifiers());
	}


	/**
	 * 业务作用：是否是synchronized方法
	 *
	 * @param method 方法
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isSynchronized(Method method) {
		return Modifier.isSynchronized(method.getModifiers());
	}


	/**
	 * 业务作用：是否是非synchronized方法
	 *
	 * @param method 方法
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isNotSynchronized(Method method) {
		return !isSynchronized(method);
	}


	/**
	 * 业务作用：是否是interface接口
	 *
	 * @param method 方法
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isInterface(Method method) {
		return Modifier.isInterface(method.getModifiers());
	}


	/**
	 * 业务作用：是否是非interface接口
	 *
	 * @param method 方法
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isNotInterface(Method method) {
		return !isInterface(method);
	}


	/**
	 * 业务作用：是否是interface接口类
	 *
	 * @param clazz 接口类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isInterface(Class<?> clazz) {
		return Modifier.isInterface(clazz.getModifiers());
	}


	/**
	 * 业务作用：是否是非interface接口类
	 *
	 * @param clazz 接口类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isNotInterface(Class<?> clazz) {
		return !isInterface(clazz);
	}


	/**
	 * 业务作用：是否是abstract抽象方法
	 *
	 * @param method 方法
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isAbstract(Method method) {
		return Modifier.isAbstract(method.getModifiers());
	}


	/**
	 * 业务作用：是否是非abstract抽象方法
	 *
	 * @param method 方法
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isNotAbstract(Method method) {
		return !isAbstract(method);
	}


	/**
	 * 业务作用：是否是abstract抽象类
	 *
	 * @param clazz 类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isAbstract(Class<?> clazz) {
		return Modifier.isAbstract(clazz.getModifiers());
	}


	/**
	 * 业务作用：是否是非abstract抽象类
	 *
	 * @param clazz 类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isNotAbstract(Class<?> clazz) {
		return !isAbstract(clazz);
	}


	/**
	 * 业务作用：是否是枚举类
	 *
	 * @param clazz 类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isEnum(Class<?> clazz) {
		return Enum.class.isAssignableFrom(clazz);
	}


	/**
	 * 业务作用：是否非枚举类
	 *
	 * @param clazz 类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isNotEnum(Class<?> clazz) {
		return !isEnum(clazz);
	}


	/**
	 * 业务作用：是否实现了 Serializable 接口
	 *
	 * @param clazz 类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isSerial(Class<?> clazz) {
		return Serializable.class.isAssignableFrom(clazz);
	}


	/**
	 * 业务作用：是否没有实现 Serializable 接口
	 *
	 * @param clazz 类
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isNotSerial(Class<?> clazz) {
		return !isSerial(clazz);
	}


	/**
	 * 业务作用：睡眠指定毫秒，让出cpu到指定时间，阻塞挂起
	 * 无需在锁内，所以不释放锁
	 *
	 * @param millis 毫秒
	 * 返回: 无返回值。
	 */
	public static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ignore) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 业务作用：休眠指定时长并吞掉中断异常，用于重试退避等不关心中断的场景。注意这会清除线程的中断标志，需要响应中断的路径不得使用。
	 *
	 * @param millis 毫秒数
	 * 返回: 无返回值。
	 */
	public static void sleepIgnore(long millis) {
		sleep(millis);
	}


	/**
	 * 业务作用：休眠指定毫秒，让出cpu到指定时间，或者被notify唤醒，阻塞挂起，会释放锁
	 * 必须在 synchronized 锁内，到时间被唤醒会重新去获取锁才能继续执行
	 *
	 * @param millis 毫秒
	 * 返回: 无返回值。
	 */
	public static void waitMillis(long millis) {
		waitMillisLock.lock();
		try {
			waitMillisCondition.await(millis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			throw new ReflectException(e.getMessage(), e);
		} finally {
			waitMillisLock.unlock();
		}
	}


	/**
	 * 业务作用：休眠指定毫秒，让出cpu到指定时间，或者被notify唤醒，阻塞挂起，会释放锁
	 * 调用方必须持有 lock 的 monitor (在 synchronized(lock) 块内调用),
	 * 否则抛 IllegalMonitorStateException
	 *
	 * @param lock synchronized锁住的对象
	 * @param millis 毫秒
	 * 返回: 无返回值。
	 */
	public static void waitMillis(Object lock, long millis) {
		try {
			lock.wait(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 让出cpu，随后进入就绪状态，下次cpu调度有可能又调度到刚刚让出cpu的线程，不会阻塞
	 */
	public static void yield() {
		Thread.yield();
	}


	/**
	 * 业务作用: 使用指定类加载器解析类名，并允许可选依赖缺失时安全降级。
	 *
	 * @param isPresent true 异常时忽略并返回null，false 抛出ReflectException
	 * @param className 类全路径
	 * @param classLoader 类加载器
	 * @return 解析出的类；允许忽略且类不存在时返回 {@code null}
	 */
	public static Class<?> forName(boolean isPresent, String className, ClassLoader classLoader) {
		try {
			return Class.forName(className, false, classLoader);
		} catch (Exception e) {
			if (isPresent) return null;
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 业务作用: 使用核心库类加载器解析必需类型，缺失时转换为统一反射异常。
	 *
	 * @param className 类全路径
	 * @return 解析出的类；空类名返回 {@code null}
	 */
	public static Class<?> forName(String className) {
		if (StringUtils.isBlank(className)) return null;
		try {
			return Class.forName(className, false, ReflectUtils.class.getClassLoader());
		} catch (ClassNotFoundException e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}

	/**
	 * 业务作用：按元素类型与长度创建数组，供泛型场景绕开数组无法直接 new 的限制。
	 *
	 * @param clazz 目标类型
	 * @param length 长度
	 * 返回: 新建的数组。
	 */
	@SuppressWarnings("unchecked")
	public static <T> T[] newArray(Class<?> clazz, int length) {
		// 排除Lambda生成的class
		if (clazz.getSimpleName().contains("$$Lambda$")) {
			clazz = clazz.getInterfaces()[0];
		}
		return (T[]) Array.newInstance(clazz, length);
	}

	/**
	 * 业务作用：反射创建实例，要求目标类型有可访问的无参构造。
	 *
	 * @param clazz 目标类型
	 * 返回: 新实例；无可用构造或构造抛错时抛出 ReflectException。
	 */
	public static <T> T newInstance(Class<T> clazz) {
		try {
			return clazz.getDeclaredConstructor().newInstance();
		} catch (Exception e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}

	/**
	 * 业务作用：反射创建实例，失败时返回 null 而不抛异常，供可选依赖探测使用。
	 *
	 * @param clazz 目标类型
	 * 返回: 新实例；创建失败时返回 null。
	 */
	public static <T> T newInstanceOrNull(Class<T> clazz) {
		try {
			return clazz.getDeclaredConstructor().newInstance();
		} catch (Throwable e) {
			return null;
		}
	}

	/**
	 * 业务作用：反射创建实例，要求目标类型有可访问的无参构造。
	 *
	 * @param t 元素
	 * 返回: 新实例；无可用构造或构造抛错时抛出 ReflectException。
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <T> T newInstance(T t) {
		if (Objects.isNull(t) || t instanceof CharSequence || t instanceof Number || t instanceof Boolean) {
			return t;
		}
		try {
			Class<?> clazz = t.getClass();
			if (t instanceof Collection c) {
				Collection nc = (Collection) newInstance(clazz);
				c.forEach(o -> nc.add(newInstance(o)));
				return (T) nc;
			}
			if (t instanceof Map m) {
				Map nm = (Map) newInstance(clazz);
				m.forEach((k, v) -> nm.put(newInstance(k), newInstance(v)));
				return (T) nm;
			}
			if (ColUtils.isArray(t)) {
				int length = Array.getLength(t);
				Object nt = newArray(clazz.getComponentType(), length);
				for (int i = 0; i < length; i++) {
					Array.set(nt, i, newInstance(Array.get(t, i)));
				}
				return (T) nt;
			}
			T nt = (T) newInstance(clazz);
			allField(clazz).forEach(f -> fieldSet(nt, f, newInstance(fieldGet(t, f))));
			return nt;
		} catch (Throwable e) {
			return t;
		}
	}

	// ==================== 基本类型判断 ====================

	private static final Set<Class<?>> BASIC_TYPES = Set.of(
			boolean.class, byte.class, short.class, int.class, long.class, float.class, double.class, char.class,
			Boolean.class, Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class, Character.class,
			String.class, BigDecimal.class, BigInteger.class, Date.class
	);

	/**
	 * 业务作用：是否是基本类型 (基本类型 + 包装类 + String + BigDecimal + BigInteger + Date)
	 *
	 * @param clazz 见上述说明
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isBasicType(Class<?> clazz) {
		return BASIC_TYPES.contains(clazz);
	}

	/**
	 * 业务作用：是否不是基本类型
	 *
	 * @param clazz 见上述说明
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean isNotBasicType(Class<?> clazz) {
		return !isBasicType(clazz);
	}

	// ==================== 字段/方法存在性检查 ====================

	/**
	 * 业务作用：类是否包含指定名称的字段 (含父类)
	 *
	 * @param clazz 见上述说明
	 * @param fieldName 见上述说明
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean hasField(Class<?> clazz, String fieldName) {
		while (clazz != Object.class) {
			try {
				clazz.getDeclaredField(fieldName);
				return true;
			} catch (NoSuchFieldException ignored) {
				clazz = clazz.getSuperclass();
			}
		}
		return false;
	}

	/**
	 * 业务作用：类是否包含指定名称的方法 (含父类)
	 *
	 * @param clazz 见上述说明
	 * @param methodName 见上述说明
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean hasMethod(Class<?> clazz, String methodName) {
		while (clazz != Object.class) {
			for (Method m : clazz.getDeclaredMethods()) {
				if (m.getName().equals(methodName)) return true;
			}
			clazz = clazz.getSuperclass();
		}
		return false;
	}

	// ==================== 属性拷贝 ====================

	/**
	 * 业务作用：浅拷贝非 null 属性: src → target (同名同类型字段)
	 *
	 * @param src 见上述说明
	 * @param target 见上述说明
	 * 返回: 无返回值。
	 */
	public static void copyNonNull(Object src, Object target) {
		List<Field> srcFields = allField(src.getClass());
		Class<?> targetClass = target.getClass();
		for (Field sf : srcFields) {
			Object val = fieldGet(src, sf);
			if (val == null) continue;
			if (!hasField(targetClass, sf.getName())) continue;
			try {
				Field tf = targetClass.getDeclaredField(sf.getName());
				if (tf.getType() == sf.getType()) {
					fieldSet(target, tf, val);
				}
			} catch (NoSuchFieldException ignored) {
				// 跳过
			}
		}
	}

	/**
	 * 业务作用：把源对象的全部字段浅拷贝到新实例，字段引用的对象本身不复制。
	 *
	 * @param src 见上述说明
	 * 返回: 字段值相同的新实例。
	 */
	@SuppressWarnings("unchecked")
	public static <T> T shallowCopy(T src) {
		T target = (T) newInstance(src.getClass());
		List<Field> fields = allField(src.getClass());
		for (Field f : fields) {
			fieldSet(target, f, fieldGet(src, f));
		}
		return target;
	}

	// ==================== 注解查找 ====================

	/**
	 * 业务作用：在类层次中按当前类、父类、接口的顺序查找注解，使标注在父类或接口上的注解也能被找到。
	 *
	 * @param clazz 目标类型
	 * @param annotationType 见上述说明
	 * 返回: 找到的注解；整个层次中都不存在时返回 null。
	 */
	public static <A extends Annotation> A findAnnotation(Class<?> clazz, Class<A> annotationType) {
		while (clazz != null && clazz != Object.class) {
			A ann = clazz.getAnnotation(annotationType);
			if (ann != null) return ann;
			// 检查接口
			for (Class<?> iface : clazz.getInterfaces()) {
				ann = iface.getAnnotation(annotationType);
				if (ann != null) return ann;
			}
			clazz = clazz.getSuperclass();
		}
		return null;
	}

	/**
	 * 业务作用：类或其父类/接口是否标注了指定注解
	 *
	 * @param clazz 见上述说明
	 * @param annotationType 见上述说明
	 * 返回: 满足上述判定条件时返回 true，否则返回 false。
	 */
	public static boolean hasAnnotation(Class<?> clazz, Class<? extends Annotation> annotationType) {
		return findAnnotation(clazz, annotationType) != null;
	}

	// ==================== Getter/Setter 便捷调用 ====================

	/**
	 * 业务作用：按字段名调用对应的 getter，自动在 getXxx 与 isXxx 之间选择。
	 *
	 * @param obj 目标对象
	 * @param fieldName 字段名
	 * 返回: getter 返回值；两种方法都不存在时抛出 ReflectException。
	 */
	public static Object invokeGetter(Object obj, String fieldName) {
		String getterName = toGetterName(fieldName);
		try {
			return obj.getClass().getMethod(getterName).invoke(obj);
		} catch (NoSuchMethodException e) {
			// 尝试 is 前缀 (boolean 属性)
			try {
				return obj.getClass().getMethod(toIsName(fieldName)).invoke(obj);
			} catch (Exception ex) {
				throw new ReflectException("No getter for field: " + fieldName, ex);
			}
		} catch (Exception e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}

	/**
	 * 业务作用：通过字段名调用 setter: fieldName="age", value=18 → setAge(18)
	 *
	 * @param obj 见上述说明
	 * @param fieldName 见上述说明
	 * @param value 见上述说明
	 * 返回: 无返回值。
	 */
	public static void invokeSetter(Object obj, String fieldName, Object value) {
		String setterName = toSetterName(fieldName);
		try {
			for (Method m : obj.getClass().getMethods()) {
				if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
					m.invoke(obj, value);
					return;
				}
			}
			throw new ReflectException("No setter for field: " + fieldName);
		} catch (ReflectException e) {
			throw e;
		} catch (Exception e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}

	// ==================== 泛型解析 ====================

	/**
	 * 业务作用：取出类继承的父类或实现的接口上的泛型实参，用于在运行期还原被擦除的类型信息。
	 *
	 * @param clazz 目标类型
	 * @param index 下标
	 * 返回: 该位置的泛型实参类型；无法确定时抛出 ReflectException。
	 */
	public static Class<?> getSuperGenericType(Class<?> clazz, int index) {
		java.lang.reflect.Type genType = clazz.getGenericSuperclass();
		if (genType instanceof java.lang.reflect.ParameterizedType pt) {
			java.lang.reflect.Type[] params = pt.getActualTypeArguments();
			if (index < params.length && params[index] instanceof Class<?> c) {
				return c;
			}
		}
		return null;
	}

	/**
	 * 业务作用：取出类继承的父类或实现的接口上的泛型实参，用于在运行期还原被擦除的类型信息。
	 *
	 * @param clazz 目标类型
	 * 返回: 该位置的泛型实参类型；无法确定时抛出 ReflectException。
	 */
	public static Class<?> getSuperGenericType(Class<?> clazz) {
		return getSuperGenericType(clazz, 0);
	}

}
