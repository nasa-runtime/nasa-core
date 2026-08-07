package io.github.nasaruntime.core.copier.string.concat;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Nasa
 */
public abstract class ACopier {


    /* 复制器缓存 */
    private static final Map<String, ACopier> copierMap = new HashMap<>();

    static {
        BooleanArrayCopier booleanArrayCopier = new BooleanArrayCopier();
        copierMap.put(booleanArrayCopier.copier(), booleanArrayCopier);

        BooleanCopier booleanCopier = new BooleanCopier();
        copierMap.put(booleanCopier.copier(), booleanCopier);

        BooleansCopier booleansCopier = new BooleansCopier();
        copierMap.put(booleansCopier.copier(), booleansCopier);

        ByteArrayCopier byteArrayCopier = new ByteArrayCopier();
        copierMap.put(byteArrayCopier.copier(), byteArrayCopier);

        ByteCopier byteCopier = new ByteCopier();
        copierMap.put(byteCopier.copier(), byteCopier);

        BytesCopier bytesCopier = new BytesCopier();
        copierMap.put(bytesCopier.copier(), bytesCopier);

        CharacterArrayCopier characterArrayCopier = new CharacterArrayCopier();
        copierMap.put(characterArrayCopier.copier(), characterArrayCopier);

        CharacterCopier characterCopier = new CharacterCopier();
        copierMap.put(characterCopier.copier(), characterCopier);

        CharsCopier charsCopier = new CharsCopier();
        copierMap.put(charsCopier.copier(), charsCopier);

        CharSequenceCopier charSequenceCopier = new CharSequenceCopier();
        copierMap.put(charSequenceCopier.copier(), charSequenceCopier);

        CollectionCopier collectionCopier = new CollectionCopier();
        copierMap.put(collectionCopier.copier(), collectionCopier);

        IntegerArrayCopier integerArrayCopier = new IntegerArrayCopier();
        copierMap.put(integerArrayCopier.copier(), integerArrayCopier);

        IntegerCopier integerCopier = new IntegerCopier();
        copierMap.put(integerCopier.copier(), integerCopier);

        IntsCopier intsCopier = new IntsCopier();
        copierMap.put(intsCopier.copier(), intsCopier);

        LongArrayCopier longArrayCopier = new LongArrayCopier();
        copierMap.put(longArrayCopier.copier(), longArrayCopier);

        LongCopier longCopier = new LongCopier();
        copierMap.put(longCopier.copier(), longCopier);

        LongsCopier longsCopier = new LongsCopier();
        copierMap.put(longsCopier.copier(), longsCopier);

        ObjectArrayCopier objectArrayCopier = new ObjectArrayCopier();
        copierMap.put(objectArrayCopier.copier(), objectArrayCopier);

        ObjectCopier objectCopier = new ObjectCopier();
        copierMap.put(objectCopier.copier(), objectCopier);

        ShortArrayCopier shortArrayCopier = new ShortArrayCopier();
        copierMap.put(shortArrayCopier.copier(), shortArrayCopier);

        ShortCopier shortCopier = new ShortCopier();
        copierMap.put(shortCopier.copier(), shortCopier);

        ShortsCopier shortsCopier = new ShortsCopier();
        copierMap.put(shortsCopier.copier(), shortsCopier);

        StringArrayCopier stringArrayCopier = new StringArrayCopier();
        copierMap.put(stringArrayCopier.copier(), stringArrayCopier);

        StringCopier stringCopier = new StringCopier();
        copierMap.put(stringCopier.copier(), stringCopier);
    }


    /**
     * 业务作用：注册或覆盖一个类型复制器，供调用方为自定义类型接入零中间字符串拼接。
     * 键必须与源数据的 {@code getClass().getSimpleName()} 一致，否则永远匹配不上。
     *
     * @param key 源数据类型的简单类名
     * @param copier 该类型的复制器实现，为 null 时忽略本次注册
     * 返回: 无返回值；同键重复注册按后者覆盖。
     */
    @SuppressWarnings("unused")
    public static void addCopier(String key, ACopier copier) {
        if (Objects.isNull(copier)) {
            return;
        }
        
        copierMap.put(key, copier);
    }


    /**
     * 业务作用：为源数据类型选出复制器，按精确类名、字符序列、集合、兜底对象四级依次匹配。
     * 分级顺序不可调换：CharSequence 与 Collection 的实现类无法逐个登记，
     * 必须在退化到通用对象复制器之前先按接口归类，否则它们会走上低效的 toString 路径。
     *
     * @param clazz 源数据的运行时类型
     * 返回: 匹配到的复制器；没有任何专用实现时返回通用对象复制器，绝不返回 null。
     */
    public static ACopier getCopier(Class<?> clazz) {


        // 获取源对应的复制器
        ACopier copier = copierMap.get(clazz.getSimpleName());
        if (Objects.nonNull(copier)) {
            return copier;
        }

        // 字符串类
        if (CharSequence.class.isAssignableFrom(clazz)) {
            return copierMap.get(CharSequenceCopier.Copier);
        }

        // 集合类
        if (Collection.class.isAssignableFrom(clazz)) {
            return copierMap.get(CollectionCopier.Copier);
        }

        // 其它类
        return copierMap.get(ObjectCopier.Copier);
    }


    /**
     * 业务作用：由各实现给出自身在注册表中的类型键，是类型到复制器的唯一路由依据。
     *
     * 参数说明: 无。
     * 返回: 类型键，通常等于目标类型的简单类名。
     */
    public abstract String copier();


    /**
     * 业务作用：由各实现预先算出源数据拼接后占用的字符数，使拼接方能一次性分配足够的字符数组。
     *
     * @param source 待测量的源数据，允许为 null
     * 返回: 所需字符数；实现必须对 null 返回 0 而不是抛异常。
     */
    public abstract int length(Object source);


    /**
     * 业务作用：由各实现把源数据的字符表示写入目标数组，是零中间字符串拼接的核心步骤。
     * 实现不做边界检查，前置条件是调用方已按 length 的结果分配好数组。
     *
     * @param source 待写入的源数据，允许为 null
     * @param cs 目标字符数组
     * @param start 本次写入的起始下标
     * 返回: 写入结束后的下标；实现必须对 null 原样返回 start。
     */
    public abstract int copyToCharArray(Object source, char[] cs, int start);

}
