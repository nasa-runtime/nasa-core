package com.nasa.runtime.core.copier.string.concat;

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
     * 添加复制器，重复的key将覆盖
     * @param key copier对应数据类型的getClass().getSimpleName()
     * @param copier 复制器
     */
    @SuppressWarnings("unused")
    public static void addCopier(String key, ACopier copier) {
        if (Objects.isNull(copier)) {
            return;
        }
        
        copierMap.put(key, copier);
    }


    /**
     * 获取一个复制器
     * @param clazz 源数据的class
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
     * copier名称
     */
    public abstract String copier();


    /**
     * 获取源的字符串长度
     * @param source 源
     */
    public abstract int length(Object source);


    /**
     * 将源的字符串复制进字符数组
     * @param source 源
     * @param cs 字符数组
     * @param start 字符数组开始下标
     * @return 返回处理完成后字符数组下标
     */
    public abstract int copyToCharArray(Object source, char[] cs, int start);

}
