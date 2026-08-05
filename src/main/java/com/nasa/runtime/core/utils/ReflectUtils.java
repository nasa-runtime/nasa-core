package com.nasa.runtime.core.utils;

import com.nasa.runtime.core.enums.AndOr;
import com.nasa.runtime.core.enums.Reflect;
import com.nasa.runtime.core.exception.ReflectException;
import com.nasa.runtime.core.function.BooleanFunction;
import com.nasa.runtime.core.function.FunctionUtils;
import com.nasa.runtime.core.function.SerFunction;

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
	 * 获取所有的普通属性，包含除Object的所有父类
	 * {@code 非 static && 非 final}（带缓存）
	 */
	public static List<Field> allField(Class<?> clazz) {
		return FIELD_CACHE.computeIfAbsent(clazz, c -> allField(c, Reflect.IsOrdinary));
	}


	/**
	 * 获取类中属性，包含除Object的所有父类
	 * 条件都要满足
	 * @param clazz 类
	 * @param conditions 条件校验枚举，为null时，包含所有属性
	 */
	@SafeVarargs
    public static List<Field> allField(Class<?> clazz, Function<Member, Boolean>... conditions) {
		return allField(clazz, AndOr.And, conditions);
	}


	/**
	 * 获取类中属性，包含除Object的所有父类
	 * 条件都要满足
	 * @param clazz 类
	 * @param conditions 条件校验枚举，为null时，包含所有属性
	 */
	public static List<Field> allField(Class<?> clazz, Reflect... conditions) {
		return allField(clazz, AndOr.And, ColUtils.toArray(Function.class, conditions, r -> true, Reflect::getApplier));
	}


	/**
	 * 获取类中属性，包含除Object的所有父类
	 * @param clazz 类
	 * @param andOr AndOrEnum.And 表示都要满足，AndOrEnum.Or 表示满足一个
	 * @param conditions 条件校验枚举，为null时，包含所有属性
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
	 * 获取所有的getter方法，包含boolean属性的is方法 (带缓存)
	 */
	public static List<Method> allGetter(Class<?> clazz) {
		return GETTER_CACHE.computeIfAbsent(clazz, c -> allMethod(c, Reflect.IsGetter.getApplier()));
	}


	/**
	 * 获取所有的setter方法 (带缓存)
	 */
	public static List<Method> allSetter(Class<?> clazz) {
		return SETTER_CACHE.computeIfAbsent(clazz, c -> allMethod(c, Reflect.IsSetter.getApplier()));
	}


	/**
	 * 获取类中方法，包含除Object的所有父类
	 * 条件都要满足
	 * @param clazz 类
	 */
	public static List<Method> allMethod(Class<?> clazz) {
		return allMethod(clazz, AndOr.And, (Function<Member, Boolean>[]) null);
	}


	/**
	 * 获取类中方法，包含除Object的所有父类
	 * 条件都要满足
	 * @param clazz 类
	 * @param conditions 条件校验枚举，为null时，包含所有属性
	 */
	@SafeVarargs
    public static List<Method> allMethod(Class<?> clazz, Function<Member, Boolean>... conditions) {
		return allMethod(clazz, AndOr.And, conditions);
	}


	/**
	 * 获取类中方法，包含除Object的所有父类
	 * 条件都要满足
	 * @param clazz 类
	 * @param conditions 条件校验枚举，为null时，包含所有属性
	 */
	public static List<Method> allMethod(Class<?> clazz, Reflect... conditions) {
		return allMethod(clazz, AndOr.And, ColUtils.toArray(Function.class, conditions, r -> true, Reflect::getApplier));
	}


	/**
	 * 获取类中方法，包含除Object的所有父类
	 * @param clazz 类
	 * @param andOr AndOrEnum.And 表示都要满足，AndOrEnum.Or 表示满足一个
	 * @param conditions 条件校验枚举，为null时，包含所有属性
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
	 * 公共条件匹配: And 模式全部满足, Or 模式满足一个
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
	 * 反射执行方法
	 * @param obj 源对象
	 * @param methodName 方法名称
	 * @param args 参数
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
	public static Object invoke(Object obj, Method method, Object... args) {
		try {
			return method.invoke(obj, args);
		} catch (Throwable e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 反射获取属性值
	 * @param obj 源对象
	 * @param fieldName 属性名称
	 */
	public static Object fieldGet(Object obj, String fieldName) {
		return fieldGet(obj, obj.getClass(), fieldName);
	}

	/**
	 * 反射获取属性值，如果是代理类，则需要指定target类的class
	 * @param obj 源对象
	 * @param clazz 类
	 * @param fieldName 属性名称
	 */
	public static Object fieldGet(Object obj, Class<?> clazz, String fieldName) {
		try {
			return fieldGet(obj, clazz.getDeclaredField(fieldName));
		} catch (NoSuchFieldException e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 反射获取属性值
	 * @param obj 源对象
	 * @param field 属性
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
	 * 反射设置属性值
	 * @param obj 源对象
	 * @param fieldName 属性名称
	 * @param value 值
	 */
	public static void fieldSet(Object obj, String fieldName, Object value) {
		try {
			fieldSet(obj, obj.getClass().getDeclaredField(fieldName), value);
		} catch (NoSuchFieldException e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}


	/**
	 * 反射设置属性值
	 * @param obj 源对象
	 * @param field 属性
	 * @param value 值
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
	 * 获取所有父类同名的属性，排除Object类
	 * @param clazz 类
	 * @param fieldName 属性名
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
	 * 获取所有父类同名的属性，排除Object类
	 * @param list 集合
	 * @param clazz 类
	 * @param fieldName 属性名
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
	 * 反射获取静态属性值
	 * @param clazz 类
	 * @param fieldName 属性名
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
	 * 反射设置静态属性值
	 * @param clazz 类
	 * @param fieldName 属性名
	 * @param value 值
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
	 * 执行静态方法
	 * @param cla 静态方法所在类
	 * @param sm 静态方法名称
	 */
	@SuppressWarnings("all")
	public static Object invokeStatic(Class<?> cla, String sm) {
		return invokeStatic(cla, sm, null);
	}


	/**
	 * 执行静态方法
	 * @param cla 静态方法所在类
	 * @param sm 静态方法名称
	 * @param classes 静态方法参数类型
	 * @param args 静态方法参数
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
	 * 获取指定目录下的class集合
	 * @param path 目录地址：类似com.nasa.runtime
	 */
	@SafeVarargs
	public static ArrayList<Class<Object>> allClasses(String path, BooleanFunction<Class<Object>>... bfs) {
		return classesOfType(path, null, bfs);
	}


	/**
	 * 获取指定目录下的class集合，忽略掉不存在的class
	 * @param path 目录地址：类似com.nasa.runtime
	 */
	@SafeVarargs
	public static ArrayList<Class<Object>> allClassesIfPresent(String path, BooleanFunction<Class<Object>>... bfs) {
		return allClassesIfPresent(path, null, bfs);
	}


	/**
	 * 获取指定目录下的class集合，忽略掉不存在的class
	 * 不依赖于framework上下文，效率低于classesOfType
	 * @param path 目录地址：类似com.nasa.runtime
	 */
	@SafeVarargs
	public static <T> ArrayList<Class<T>> allClassesIfPresent(String path, Class<T> type, BooleanFunction<Class<T>>... bfs) {
		return classesOfType(Thread.currentThread().getContextClassLoader(), path, true, type, bfs);
	}


	/**
	 * 获取指定目录下，继承父类或实现接口的class集合
	 * @param path 目录地址：类似com.nasa.runtime
	 * @param type 指定接口或父类的class
	 * @param bfs 条件函数
	 * @param <T> 父类或接口的class
	 * @param <E> 继承父类或实现接口的子类泛型
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
	 * @param path 目录地址：类似com.nasa.runtime
	 * @param type 指定接口或父类的class
	 * @param bfs 条件函数
	 * @param <T> 父类或接口的class
	 * @param <E> 继承父类或实现接口的子类泛型
	 * @return 满足类型与条件约束的类集合；扫描失败且不允许忽略时抛出反射异常
	 */
	@SafeVarargs
	public static <T, E extends T> ArrayList<Class<T>> classesOfType(
			ClassLoader classLoader, String path, Class<T> type, BooleanFunction<Class<T>>... bfs) {
		return classesOfType(classLoader, path, false, type, bfs);
	}


	/**
	 * 获取指定目录下，继承父类或实现接口的class集合
	 * @param classLoader 类加载器
	 * @param path 目录地址：类似com.nasa.runtime
	 * @param isPresent true：忽略掉不存在的class，false：不忽略
	 * @param type 指定接口或父类的class
	 * @param bfs 条件函数
	 * @param <T> 父类或接口的class
	 * @param <E> 继承父类或实现接口的子类泛型
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
	 * 创建指定目录下，继承父类或实现接口的子类实例，并返回map
	 * @param type 指定接口或父类的class
	 * @param keyApplier 生成map的key的函数
	 * @param path 目录地址：类似com.nasa.runtime
	 * @param <T> 父类或接口的class
	 * @param <E> 继承父类或实现接口的子类泛型
	 * @param <K> map中key的泛型
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
	 * @param path 目录地址：类似com.nasa.runtime
	 * @param <A> 注解类型
	 * @param <T> 父类或接口的class
	 * @param <E> 继承父类或实现接口的子类泛型
	 * @param <K> map中key的泛型
	 */
	public static <A extends Annotation, T, E, K>
	Map<K, T> beanMapOfAnnotation(Class<A> clazz, SerFunction<E, K> keyApplier, String path) {
		return beanMapOfAnnotation(clazz, keyApplier, path, null);
	}


	/**
	 * 创建指定目录下，被指定注解注解的类的实例，并返回map
	 * @param clazz 注解的class
	 * @param keyApplier 生成map的key的函数
	 * @param path 目录地址：类似com.nasa.runtime
	 * @param type 返回Map的value的类型
	 * @param <A> 注解类型
	 * @param <T> 父类或接口的class
	 * @param <E> 继承父类或实现接口的子类泛型
	 * @param <K> map中key的泛型
	 */
	@SuppressWarnings("unchecked")
	public static <A extends Annotation, T, E, K>
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
	 * 获取实例中属性为null的属性名称
	 * {@code 非 static && 非 final}
	 * @param source 实例
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
	 * 获取实例中属性不为null的属性名称
	 * {@code 非 static && 非 final}
	 * @param source 实例
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
	 * 获取对象的方法名称
	 * @param getterFunction 对象的get/is函数，如：AppCollector::getObj
	 * @param <T> 具体对象的泛型
	 * @param <R> 对象的方法返回值泛型
	 */
	public static <T, R> String fieldName(SerFunction<T, R> getterFunction) {
		return FunctionUtils.fieldName(getterFunction);
	}


	/**
	 * 获取Method对应的属性名称
	 * prefix |= 32		大写转小写，并赋值
	 * {@code prefix &= -33}	小写转大写，并赋值
	 * prefix ^= 32		大写转小写，小写转大写，并赋值
	 *
	 * @param getter get或者is方法，如：getName、isEnable
	 */
	public static String fieldName(Method getter) {
		return fieldName(getter.getName());
	}


	/**
	 * 获取 get或者is方法 对应的属性名称
	 * prefix |= 32		大写转小写，并赋值
	 * {@code prefix &= -33}	小写转大写，并赋值
	 * prefix ^= 32		大写转小写，小写转大写，并赋值
	 *
	 * @param getterName get或者is方法
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
	 * 由属性名获取对应方法的setter方法名
	 * prefix |= 32		大写转小写，并赋值
	 * {@code prefix &= -33}	小写转大写，并赋值
	 * prefix ^= 32		大写转小写，小写转大写，并赋值
	 * @param fieldName 属性名
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
	 * 由属性名获取对应方法的getter方法名
	 * prefix |= 32		大写转小写，并赋值
	 * {@code prefix &= -33}	小写转大写，并赋值
	 * prefix ^= 32		大写转小写，小写转大写，并赋值
	 * @param fieldName 属性名
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
	 * 由属性名获取对应方法的is方法名
	 * prefix |= 32		大写转小写，并赋值
	 * {@code prefix &= -33}	小写转大写，并赋值
	 * prefix ^= 32		大写转小写，小写转大写，并赋值
	 * @param fieldName 属性名
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
	 * 是否是 Lambda 表达式生成的实例 (类名含 $$Lambda)
	 * @param obj 实例对象
	 */
	public static boolean isLambda(Object obj) {
		return obj != null && obj.getClass().getName().contains("$$Lambda");
	}

	/**
	 * 是否不是 Lambda 表达式生成的实例
	 * @param obj 实例对象
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
	 * 是否是static属性/方法
	 * @param member 属性/方法
	 */
	public static boolean isStatic(Member member) {
		return Modifier.isStatic(member.getModifiers());
	}


	/**
	 * 是否是static类
	 * @param clazz 类
	 */
	public static boolean isStatic(Class<?> clazz) {
		return Modifier.isStatic(clazz.getModifiers());
	}


	/**
	 * 是否是非static属性/方法
	 * @param member 属性/方法
	 */
	public static boolean isNotStatic(Member member) {
		return !isStatic(member);
	}


	/**
	 * 是否是非static类
	 * @param clazz 类
	 */
	public static boolean isNotStatic(Class<?> clazz) {
		return !isStatic(clazz);
	}


	/**
	 * 是否是final属性/方法
	 * @param member 属性/方法
	 */
	public static boolean isFinal(Member member) {
		return Modifier.isFinal(member.getModifiers());
	}


	/**
	 * 是否是final类
	 * @param clazz 类
	 */
	public static boolean isFinal(Class<?> clazz) {
		return Modifier.isFinal(clazz.getModifiers());
	}


	/**
	 * 是否是非final属性/方法
	 * @param member 属性/方法
	 */
	public static boolean isNotFinal(Member member) {
		return !isFinal(member);
	}


	/**
	 * 是否是非final类
	 * @param clazz 类
	 */
	public static boolean isNotFinal(Class<?> clazz) {
		return !isFinal(clazz);
	}


	/**
	 * 是否是public属性/方法
	 * @param member 属性/方法
	 */
	public static boolean isPublic(Member member) {
		return Modifier.isPublic(member.getModifiers());
	}


	/**
	 * 是否是public类
	 * @param clazz 类
	 */
	public static boolean isPublic(Class<?> clazz) {
		return Modifier.isPublic(clazz.getModifiers());
	}


	/**
	 * 是否是private属性/方法
	 * @param member 属性/方法
	 */
	public static boolean isPrivate(Member member) {
		return Modifier.isPrivate(member.getModifiers());
	}


	/**
	 * 是否是private类
	 * @param clazz 类
	 */
	public static boolean isPrivate(Class<?> clazz) {
		return Modifier.isPrivate(clazz.getModifiers());
	}


	/**
	 * 是否是protected属性/方法
	 * @param member 属性/方法
	 */
	public static boolean isProtected(Member member) {
		return Modifier.isProtected(member.getModifiers());
	}


	/**
	 * 是否是protected类
	 * @param clazz 类
	 */
	public static boolean isProtected(Class<?> clazz) {
		return Modifier.isProtected(clazz.getModifiers());
	}


	/**
	 * 是否是synchronized方法
	 * @param method 方法
	 */
	public static boolean isSynchronized(Method method) {
		return Modifier.isSynchronized(method.getModifiers());
	}


	/**
	 * 是否是非synchronized方法
	 * @param method 方法
	 */
	public static boolean isNotSynchronized(Method method) {
		return !isSynchronized(method);
	}


	/**
	 * 是否是interface接口
	 * @param method 方法
	 */
	public static boolean isInterface(Method method) {
		return Modifier.isInterface(method.getModifiers());
	}


	/**
	 * 是否是非interface接口
	 * @param method 方法
	 */
	public static boolean isNotInterface(Method method) {
		return !isInterface(method);
	}


	/**
	 * 是否是interface接口类
	 * @param clazz 接口类
	 */
	public static boolean isInterface(Class<?> clazz) {
		return Modifier.isInterface(clazz.getModifiers());
	}


	/**
	 * 是否是非interface接口类
	 * @param clazz 接口类
	 */
	public static boolean isNotInterface(Class<?> clazz) {
		return !isInterface(clazz);
	}


	/**
	 * 是否是abstract抽象方法
	 * @param method 方法
	 */
	public static boolean isAbstract(Method method) {
		return Modifier.isAbstract(method.getModifiers());
	}


	/**
	 * 是否是非abstract抽象方法
	 * @param method 方法
	 */
	public static boolean isNotAbstract(Method method) {
		return !isAbstract(method);
	}


	/**
	 * 是否是abstract抽象类
	 * @param clazz 类
	 */
	public static boolean isAbstract(Class<?> clazz) {
		return Modifier.isAbstract(clazz.getModifiers());
	}


	/**
	 * 是否是非abstract抽象类
	 * @param clazz 类
	 */
	public static boolean isNotAbstract(Class<?> clazz) {
		return !isAbstract(clazz);
	}


	/**
	 * 是否是枚举类
	 * @param clazz 类
	 */
	public static boolean isEnum(Class<?> clazz) {
		return Enum.class.isAssignableFrom(clazz);
	}


	/**
	 * 是否非枚举类
	 * @param clazz 类
	 */
	public static boolean isNotEnum(Class<?> clazz) {
		return !isEnum(clazz);
	}


	/**
	 * 是否实现了 Serializable 接口
	 * @param clazz 类
	 */
	public static boolean isSerial(Class<?> clazz) {
		return Serializable.class.isAssignableFrom(clazz);
	}


	/**
	 * 是否没有实现 Serializable 接口
	 * @param clazz 类
	 */
	public static boolean isNotSerial(Class<?> clazz) {
		return !isSerial(clazz);
	}


	/**
	 * 睡眠指定毫秒，让出cpu到指定时间，阻塞挂起
	 * 无需在锁内，所以不释放锁
	 * @param millis 毫秒
	 */
	public static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ignore) {
			Thread.currentThread().interrupt();
		}
	}

	public static void sleepIgnore(long millis) {
		sleep(millis);
	}


	/**
	 * 休眠指定毫秒，让出cpu到指定时间，或者被notify唤醒，阻塞挂起，会释放锁
	 * 必须在 synchronized 锁内，到时间被唤醒会重新去获取锁才能继续执行
	 * @param millis 毫秒
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
	 * 休眠指定毫秒，让出cpu到指定时间，或者被notify唤醒，阻塞挂起，会释放锁
	 * 调用方必须持有 lock 的 monitor (在 synchronized(lock) 块内调用),
	 * 否则抛 IllegalMonitorStateException
	 * @param lock synchronized锁住的对象
	 * @param millis 毫秒
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
	 * 创建一个新的数组
	 * @param clazz 实例类型
	 * @param length 数组长度
	 * @param <T> 实例泛型
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
	 * 反射创建实例
	 * @param clazz 实例类型
	 * @param <T> 实例泛型
	 */
	public static <T> T newInstance(Class<T> clazz) {
		try {
			return clazz.getDeclaredConstructor().newInstance();
		} catch (Exception e) {
			throw new ReflectException(e.getMessage(), e);
		}
	}

	/**
	 * 反射创建实例, 失败返回 null
	 * @param clazz 实例类型
	 * @param <T> 实例泛型
	 */
	public static <T> T newInstanceOrNull(Class<T> clazz) {
		try {
			return clazz.getDeclaredConstructor().newInstance();
		} catch (Throwable e) {
			return null;
		}
	}

	/**
	 * 生成一个新的对象返回
	 * @param t 实例对象
	 * @param <T> 实例泛型
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
	 * 是否是基本类型 (基本类型 + 包装类 + String + BigDecimal + BigInteger + Date)
	 */
	public static boolean isBasicType(Class<?> clazz) {
		return BASIC_TYPES.contains(clazz);
	}

	/**
	 * 是否不是基本类型
	 */
	public static boolean isNotBasicType(Class<?> clazz) {
		return !isBasicType(clazz);
	}

	// ==================== 字段/方法存在性检查 ====================

	/**
	 * 类是否包含指定名称的字段 (含父类)
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
	 * 类是否包含指定名称的方法 (含父类)
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
	 * 浅拷贝非 null 属性: src → target (同名同类型字段)
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
	 * 浅拷贝所有属性到新实例
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
	 * 在类层次中查找注解 (当前类 → 父类 → 接口)
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
	 * 类或其父类/接口是否标注了指定注解
	 */
	public static boolean hasAnnotation(Class<?> clazz, Class<? extends Annotation> annotationType) {
		return findAnnotation(clazz, annotationType) != null;
	}

	// ==================== Getter/Setter 便捷调用 ====================

	/**
	 * 通过字段名调用 getter: fieldName="age" → getAge() / isAge()
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
	 * 通过字段名调用 setter: fieldName="age", value=18 → setAge(18)
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
	 * 获取类继承/实现的泛型参数类型
	 * 如: {@code class UserService extends BaseService<User>} → 返回 User.class
	 * @param index 泛型参数下标 (从 0 开始)
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
	 * 获取类继承的第一个泛型参数类型
	 */
	public static Class<?> getSuperGenericType(Class<?> clazz) {
		return getSuperGenericType(clazz, 0);
	}

}
