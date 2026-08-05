package com.nasa.runtime.core.feature;

import com.nasa.runtime.core.concurrent.ConcurrentHashSet;
import com.nasa.runtime.core.exception.ReflectException;
import com.nasa.runtime.core.function.BooleanFunction;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeSerialization;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * 为 GraalVM native-image 注册核心集合类型和应用可序列化类型。
 * 应用扫描包通过系统属性 {@code nasa.native.serialization.package} 显式指定。
 */
@SuppressWarnings("unused")
public class NasaNativeFeature implements Feature {

    private static final Logger log = Logger.getLogger(NasaNativeFeature.class.getName());

    /**
     * 业务作用: 在原生镜像分析阶段注册内建类型，并仅扫描调用方明确指定的应用包以控制镜像体积。
     *
     * @param access 原生镜像构建阶段访问对象
     * 返回: 无；类型注册结果写入当前原生镜像构建上下文。
     */
    @Override
    public void duringSetup(DuringSetupAccess access) {

        // 注册基本类型的封装类
        Stream.of(
                Long.class
                , Integer.class
                , Byte.class
                , Short.class
                , Float.class
                , Double.class
                , BigDecimal.class
                , BigInteger.class

                , String.class
                , Character.class
                , StringBuilder.class
                , StringBuffer.class

                , Boolean.class
                , AtomicBoolean.class

                , AtomicLong.class
                , AtomicLongArray.class
                , AtomicInteger.class
                , AtomicIntegerArray.class
                , AtomicReference.class
                , AtomicReferenceArray.class

                , HashMap.class
                , LinkedHashMap.class
                , ConcurrentHashMap.class
                , HashSet.class
                , LinkedHashSet.class
                , ArrayList.class
                , LinkedList.class
                , TreeSet.class
                , TreeMap.class

                , ConcurrentHashSet.class
        ).forEach(clazz -> {
            log.fine(() -> "Register serialization bean: " + clazz.getName());
            RuntimeSerialization.register(clazz);
        });

        // 应用类型的扫描范围必须显式给出，避免原生镜像构建时遍历无关依赖并扩大镜像体积。
        String packageName = System.getProperty("nasa.native.serialization.package");
        if (isBlank(packageName)) {
            log.fine("Skip application serialization registration: scan package is not configured");
            return;
        }

        List<Class<Serializable>> classes = classesOfType(packageName, Serializable.class);
        for (Class<?> cla : classes) {
            log.fine(() -> "Register serialization bean: " + cla.getName());
            RuntimeSerialization.register(cla);
            log.fine(() -> "Register serialization lambda proxy: " + cla.getName());
            RuntimeSerialization.registerLambdaCapturingClass(cla);
        }
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


    private static int length(final CharSequence cs) {
        return Objects.isNull(cs) ? 0 : cs.length();
    }


    /**
     * 判断字符串为空
     * StringUtils.isBlank(null)      = true
     * StringUtils.isBlank("")        = true
     * StringUtils.isBlank(" ")       = true
     * StringUtils.isBlank("bob")     = false
     * StringUtils.isBlank("  bob  ") = false
     */
    public static boolean isBlank(final CharSequence cs) {
        int strLen = length(cs);
        if (strLen == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if (!Character.isWhitespace(cs.charAt(i))) {
                return false;
            }
        }
        return true;
    }


    /**
     * 判断字符串不为空
     */
    public static boolean isNotBlank(final CharSequence cs) {
        return !isBlank(cs);
    }


    /**
     * 获取指定目录下的class集合
     * 不依赖于framework上下文，效率低于ReflectUtils.classesOfType
     * @param path 目录地址：类似com.nasa.runtime
     */
    @SafeVarargs
    public static ArrayList<Class<Object>> allClasses(String path, BooleanFunction<Class<Object>>... bfs) {
        return classesOfType(path, null, bfs);
    }


    /**
     * 获取指定目录下的class集合，忽略掉不存在的class
     * 不依赖于framework上下文，效率低于ReflectUtils.classesOfType
     * @param path 目录地址：类似com.nasa.runtime
     */
    @SafeVarargs
    public static ArrayList<Class<Object>> allClassesIfPresent(String path, BooleanFunction<Class<Object>>... bfs) {
        return classesOfTypeIfPresent(path, null, bfs);
    }


    /**
     * 获取指定目录下的class集合，忽略掉不存在的class
     * 不依赖于framework上下文，效率低于ReflectUtils.classesOfType
     * @param path 目录地址：类似com.nasa.runtime
     */
    @SafeVarargs
    public static <T> ArrayList<Class<T>> classesOfTypeIfPresent(String path, Class<T> type, BooleanFunction<Class<T>>... bfs) {
        return classesOfType(Thread.currentThread().getContextClassLoader(), path, true, type, bfs);
    }


    /**
     * 获取指定目录下，继承父类或实现接口的class集合
     * 不依赖于framework上下文，效率低于ReflectUtils.classesOfType
     * @param path 目录地址：类似com.nasa.runtime
     * @param type 指定接口或父类的class
     * @param bfs 条件函数
     * @param <T> 父类或接口的class
     */
    @SafeVarargs
    public static <T> ArrayList<Class<T>> classesOfType(String path, Class<T> type, BooleanFunction<Class<T>>... bfs) {
        return classesOfType(Thread.currentThread().getContextClassLoader(), path, false, type, bfs);
    }


    /**
     * 获取指定目录下，继承父类或实现接口的class集合
     * 不依赖于framework上下文，效率低于ReflectUtils.classesOfType
     * @param classLoader 类加载器
     * @param path 目录地址：类似com.nasa.runtime
     * @param type 指定接口或父类的class
     * @param bfs 条件函数
     * @param <T> 父类或接口的class
     */
    @SafeVarargs
    public static <T> ArrayList<Class<T>> classesOfType(
            ClassLoader classLoader, String path, Class<T> type, BooleanFunction<Class<T>>... bfs) {
        return classesOfType(classLoader, path, false, type, bfs);
    }


    /**
     * 获取指定目录下，继承父类或实现接口的class集合
     * 不依赖于framework上下文，效率低于ReflectUtils.classesOfType
     * @param classLoader 类加载器
     * @param path 目录地址：类似com.nasa.runtime
     * @param isPresent true：忽略掉不存在的class，false：不忽略
     * @param type 指定接口或父类的class
     * @param bfs 条件函数
     * @param <T> 父类或接口的class
     */
    @SuppressWarnings({"unchecked"})
    public static <T> ArrayList<Class<T>> classesOfType(ClassLoader classLoader
            , String path, boolean isPresent, Class<T> type, BooleanFunction<Class<T>>... bfs) {
        ArrayList<Class<T>> list = new ArrayList<>();
        String sourcePath = path.replace(".", "/");
        Enumeration<URL> urls;
        try {
            urls = classLoader.getResources(sourcePath);
        } catch (IOException e) {
            throw new ReflectException(e.getMessage(), e);
        }
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (Objects.isNull(url)) {
                continue;
            }
            String protocol = url.getProtocol();
            if ("file".equals(protocol)) {
                String packagePath = url.getPath().replaceAll("%20", " ");
                addClass(classLoader, isPresent, list, packagePath, path, type, bfs);
                continue;
            }

            JarURLConnection jarURLConnection;
            try {
                jarURLConnection = (JarURLConnection) url.openConnection();
            } catch (IOException e) {
                throw new ReflectException(e.getMessage(), e);
            }
            if (Objects.isNull(jarURLConnection)) {
                continue;
            }
            JarFile jarFile;
            try {
                jarFile = jarURLConnection.getJarFile();
            } catch (IOException e) {
                throw new ReflectException(e.getMessage(), e);
            }
            if (Objects.isNull(jarFile)) {
                continue;
            }
            Enumeration<JarEntry> jarEntries = jarFile.entries();
            while (jarEntries.hasMoreElements()) {
                JarEntry jarEntry = jarEntries.nextElement();
                String jarEntryName = jarEntry.getName();
                if (jarEntryName.contains(sourcePath) && jarEntryName.endsWith(".class")) {
                    String className = jarEntryName.substring(0, jarEntryName.lastIndexOf("."))
                            .replaceAll("/", ".");
                    doAddClass(classLoader, isPresent, list, className, type, bfs);
                }
            }
        }
        return list;
    }

    @SafeVarargs
    @SuppressWarnings("ConstantConditions") // 抑制有可能产生空指针警告
    private static <T> void addClass(ClassLoader classLoader, boolean isPresent, List<Class<T>> classes
            , String packagePath, String packageName, Class<T> type, BooleanFunction<Class<T>>... bfs) {
        File[] files = new File(packagePath)
                .listFiles(file -> (file.isFile() && file.getName().endsWith(".class")) || file.isDirectory());
        for (File file : files) {
            String fileName = file.getName();
            if (file.isFile()) {
                String className = fileName.substring(0, fileName.lastIndexOf("."));
                if (isNotBlank(packageName)) {
                    className = packageName + "." + className;
                }
                doAddClass(classLoader, isPresent, classes, className, type, bfs);
                continue;
            }
            String subPackagePath = fileName;
            if (isNotBlank(packagePath)) {
                subPackagePath = packagePath + "/" + subPackagePath;
            }
            String subPackageName = fileName;
            if (isNotBlank(packageName)) {
                subPackageName = packageName + "." + subPackageName;
            }
            addClass(classLoader, isPresent, classes, subPackagePath, subPackageName, type, bfs);
        }
    }

    @SuppressWarnings({"unchecked"})
    private static <T> void doAddClass(ClassLoader classLoader, boolean isPresent
            , List<Class<T>> classes , String className, Class<T> type, BooleanFunction<Class<T>>... bfs) {
        Class<?> clazz = forName(isPresent, className, false, classLoader);
        if (Objects.isNull(clazz)) {
            return;
        }
        // 判断是否有指定父类或接口
        if (Objects.nonNull(type) && (clazz == type || !type.isAssignableFrom(clazz))) {
            return;
        }
        Class<T> cla = (Class<T>) clazz;
        for (BooleanFunction<Class<T>> bf : bfs) {
            if (!bf.apply(cla)) {
                return;
            }
        }
        classes.add(cla);
    }

    /**
     * 类存在返回true，不存在返回false
     * @param className 类全路径
     */
    public static boolean isPresent(String className, ClassLoader classLoader) {
        return Objects.nonNull(forName(true, className, false, classLoader));
    }

    /**
     * @param isPresent true 异常时忽略并返回null，false 抛出ReflectException
     * @param className 类全路径
     */
    public static Class<?> forName(boolean isPresent, String className, boolean initialize, ClassLoader classLoader) {
        try {
            return Class.forName(className, initialize, classLoader);
        } catch (Throwable e) {
            if (isPresent) {
                return null;
            }
            throw new ReflectException(e.getMessage(), e);
        }
    }

}
