package io.github.nasaruntime.core.feature;

import io.github.nasaruntime.core.concurrent.ConcurrentHashSet;
import io.github.nasaruntime.core.exception.ReflectException;
import io.github.nasaruntime.core.function.BooleanFunction;
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
     * 业务作用：判定类型是否为枚举，供扫描时按类别筛选注册对象。
     *
     * @param clazz 待判定类型
     * 返回: 是枚举返回 true。
     */
    public static boolean isEnum(Class<?> clazz) {
        return Enum.class.isAssignableFrom(clazz);
    }


    /**
     * 业务作用：判定类型是否为非枚举，供筛选条件直接以否定形式表达。
     *
     * @param clazz 待判定类型
     * 返回: 不是枚举返回 true。
     */
    public static boolean isNotEnum(Class<?> clazz) {
        return !isEnum(clazz);
    }


    /**
     * 业务作用：判定类型是否为抽象类。抽象类不能被实例化，通常不需要注册序列化。
     *
     * @param clazz 待判定类型
     * 返回: 是抽象类返回 true。
     */
    public static boolean isAbstract(Class<?> clazz) {
        return Modifier.isAbstract(clazz.getModifiers());
    }


    /**
     * 业务作用：判定类型是否为非抽象类，是筛选可实例化类型的常用条件。
     *
     * @param clazz 待判定类型
     * 返回: 不是抽象类返回 true。
     */
    public static boolean isNotAbstract(Class<?> clazz) {
        return !isAbstract(clazz);
    }


    /**
     * 业务作用：判定类型是否为接口。接口没有实例状态，通常不需要注册序列化。
     *
     * @param clazz 待判定类型
     * 返回: 是接口返回 true。
     */
    public static boolean isInterface(Class<?> clazz) {
        return Modifier.isInterface(clazz.getModifiers());
    }


    /**
     * 业务作用：判定类型是否为非接口，是筛选可实例化类型的常用条件。
     *
     * @param clazz 待判定类型
     * 返回: 不是接口返回 true。
     */
    public static boolean isNotInterface(Class<?> clazz) {
        return !isInterface(clazz);
    }


    /**
     * 业务作用：取字符序列长度并把 null 归一为 0，使空白判定无需单独处理 null 分支。
     *
     * @param cs 字符序列，允许为 null
     * 返回: 字符数；入参为 null 时返回 0。
     */
    private static int length(final CharSequence cs) {
        return Objects.isNull(cs) ? 0 : cs.length();
    }


    /**
     * 业务作用：判定字符序列是否为空白。本类刻意自带该实现而不依赖 StringUtils，
     * 因为 Feature 运行在原生镜像构建期，应尽量减少对核心库其它类型的连带加载。
     * <pre>
     *   isBlank(null)      = true
     *   isBlank("")        = true
     *   isBlank(" ")       = true
     *   isBlank("bob")     = false
     *   isBlank("  bob  ") = false
     * </pre>
     *
     * @param cs 字符序列，允许为 null
     * 返回: null、空串或全为空白字符时返回 true。
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
     * 业务作用：判定字符序列含有非空白内容。
     *
     * @param cs 字符序列，允许为 null
     * 返回: 含至少一个非空白字符时返回 true。
     */
    public static boolean isNotBlank(final CharSequence cs) {
        return !isBlank(cs);
    }


    /**
     * 业务作用：扫描指定包下的全部类型，不做父类或接口过滤。
     * 不依赖框架上下文，因此可在原生镜像构建期使用，代价是效率低于 ReflectUtils.classesOfType。
     *
     * @param path 包路径，形如 com.example.app
     * @param bfs 附加筛选条件，全部满足才收录
     * 返回: 命中的类型列表；类加载失败会抛 ReflectException。
     */
    @SafeVarargs
    public static ArrayList<Class<Object>> allClasses(String path, BooleanFunction<Class<Object>>... bfs) {
        return classesOfType(path, null, bfs);
    }


    /**
     * 业务作用：扫描指定包下的全部类型，并跳过无法加载的类。
     * 应用包中常存在依赖未引入的可选类，构建期不应因此整体失败。
     *
     * @param path 包路径，形如 com.example.app
     * @param bfs 附加筛选条件，全部满足才收录
     * 返回: 命中且可加载的类型列表；无法加载的类被静默跳过。
     */
    @SafeVarargs
    public static ArrayList<Class<Object>> allClassesIfPresent(String path, BooleanFunction<Class<Object>>... bfs) {
        return classesOfTypeIfPresent(path, null, bfs);
    }


    /**
     * 业务作用：扫描指定包下继承自某父类或实现某接口的类型，并跳过无法加载的类。
     *
     * @param path 包路径，形如 com.example.app
     * @param type 目标父类或接口；为 null 表示不做类型过滤
     * @param bfs 附加筛选条件，全部满足才收录
     * 返回: 命中且可加载的类型列表；type 自身不会被收录。
     */
    @SafeVarargs
    public static <T> ArrayList<Class<T>> classesOfTypeIfPresent(String path, Class<T> type, BooleanFunction<Class<T>>... bfs) {
        return classesOfType(Thread.currentThread().getContextClassLoader(), path, true, type, bfs);
    }


    /**
     * 业务作用：扫描指定包下继承自某父类或实现某接口的类型，遇到无法加载的类直接失败。
     * 使用线程上下文类加载器。
     *
     * @param path 包路径，形如 com.example.app
     * @param type 目标父类或接口；为 null 表示不做类型过滤
     * @param bfs 附加筛选条件，全部满足才收录
     * 返回: 命中的类型列表；任一类无法加载时抛出 ReflectException。
     */
    @SafeVarargs
    public static <T> ArrayList<Class<T>> classesOfType(String path, Class<T> type, BooleanFunction<Class<T>>... bfs) {
        return classesOfType(Thread.currentThread().getContextClassLoader(), path, false, type, bfs);
    }


    /**
     * 业务作用：以显式指定的类加载器扫描包下继承自某父类或实现某接口的类型。
     * 原生镜像构建期的类加载器可能与线程上下文类加载器不同，因此提供该入口。
     *
     * @param classLoader 用于加载候选类的类加载器
     * @param path 包路径，形如 com.example.app
     * @param type 目标父类或接口；为 null 表示不做类型过滤
     * @param bfs 附加筛选条件，全部满足才收录
     * 返回: 命中的类型列表；任一类无法加载时抛出 ReflectException。
     */
    @SafeVarargs
    public static <T> ArrayList<Class<T>> classesOfType(
            ClassLoader classLoader, String path, Class<T> type, BooleanFunction<Class<T>>... bfs) {
        return classesOfType(classLoader, path, false, type, bfs);
    }


    /**
     * 业务作用：包扫描的统一收口，同时支持普通目录与 jar 两种来源。
     * 所有 allClasses/classesOfType 重载最终都委派到这里。
     *
     * @param classLoader 用于加载候选类的类加载器
     * @param path 包路径，形如 com.example.app
     * @param isPresent true 表示跳过无法加载的类，false 表示遇到即抛 ReflectException
     * @param type 目标父类或接口；为 null 表示不做类型过滤
     * @param bfs 附加筛选条件，全部满足才收录
     * 返回: 命中的类型列表；读取类路径资源失败时抛出 ReflectException。
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

    /**
     * 业务作用：递归遍历文件系统上的包目录，把每个 class 文件交给 doAddClass 判定收录。
     * 目录不可读或路径并非目录时 listFiles 返回 null，此时视为该分支无可扫描内容并跳过——
     * 构建期不应因为一个不可达目录就抛出难以定位的空指针而中断整个镜像构建。
     *
     * @param classLoader 用于加载候选类的类加载器
     * @param isPresent true 表示跳过无法加载的类
     * @param classes 收集结果的列表，原地追加
     * @param packagePath 当前遍历的文件系统路径
     * @param packageName 与该路径对应的包名
     * @param type 目标父类或接口；为 null 表示不做类型过滤
     * @param bfs 附加筛选条件
     * 返回: 无返回值；命中的类型直接追加到 classes。
     */
    @SafeVarargs
    @SuppressWarnings("ConstantConditions")
    private static <T> void addClass(ClassLoader classLoader, boolean isPresent, List<Class<T>> classes
            , String packagePath, String packageName, Class<T> type, BooleanFunction<Class<T>>... bfs) {
        File[] files = new File(packagePath)
                .listFiles(file -> (file.isFile() && file.getName().endsWith(".class")) || file.isDirectory());
        // 路径不是目录或不可读时 listFiles 返回 null；构建期不应因一个不可达目录抛空指针中断整个镜像构建。
        if (Objects.isNull(files)) return;
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

    /**
     * 业务作用：对单个候选类名执行加载、类型过滤与条件判定，通过全部检查才收录。
     * 加载时 initialize 传 false：构建期只需类型元数据，触发静态初始化既拖慢扫描，
     * 又可能因应用类的初始化副作用导致构建失败。
     *
     * @param classLoader 用于加载候选类的类加载器
     * @param isPresent true 表示跳过无法加载的类
     * @param classes 收集结果的列表，原地追加
     * @param className 候选类的全限定名
     * @param type 目标父类或接口；为 null 表示不做类型过滤
     * @param bfs 附加筛选条件，全部满足才收录
     * 返回: 无返回值；type 自身与不满足任一条件的类型都不会被收录。
     */
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
     * 业务作用：判定类是否可加载，供构建期探测可选依赖是否存在。
     *
     * @param className 类全限定名
     * @param classLoader 用于加载的类加载器
     * 返回: 可加载返回 true；不可加载返回 false 且不抛异常。
     */
    public static boolean isPresent(String className, ClassLoader classLoader) {
        return Objects.nonNull(forName(true, className, false, classLoader));
    }

    /**
     * 业务作用：本类内部的类加载收口，由调用方决定加载失败是降级还是中断。
     *
     * @param isPresent true 表示加载失败返回 null，false 表示抛出 ReflectException
     * @param className 类全限定名
     * @param initialize 是否触发静态初始化；扫描路径一律传 false
     * @param classLoader 用于加载的类加载器
     * 返回: 加载成功的类型；失败时按 isPresent 返回 null 或抛出 ReflectException。
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
