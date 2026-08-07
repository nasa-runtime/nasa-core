package io.github.nasaruntime.core.enums;

import io.github.nasaruntime.core.utils.ReflectUtils;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Function;

/**
 * Nasa
 * 反射枚举
 */
@SuppressWarnings("all")
public enum Reflect {

    // 是否是public修饰的类、属性、接口或方法
    IsPublic (t -> Modifier.isPublic(t.getModifiers()))
    // 是否是private修饰的类、属性、接口或方法
    , IsPrivate (t -> Modifier.isPrivate(t.getModifiers()))
    // 是否是protected修饰的类、属性、接口或方法
    , IsProtected (t -> Modifier.isProtected(t.getModifiers()))
    // 是否是static修饰的类、属性、接口或方法
    , IsStatic (t -> Modifier.isStatic(t.getModifiers()))
    // 是否是final修饰的类、属性或方法
    , IsFinal (t -> Modifier.isFinal(t.getModifiers()))
    // 是否是synchronized修饰的类或方法
    , IsSynchronized (t -> Modifier.isSynchronized(t.getModifiers()))
    // 是否是volatile修饰的属性
    , IsVolatile (t -> Modifier.isVolatile(t.getModifiers()))
    // 是否是transient修饰的属性
    , IsTransient (t -> Modifier.isTransient(t.getModifiers()))
    // 是否是native修饰的接口
    , IsNative (t -> Modifier.isNative(t.getModifiers()))
    // 是否是interface修饰的类，或者接口
    , IsInterface (t -> Modifier.isInterface(t.getModifiers()))
    // 是否是抽象类、抽象方法
    , IsAbstract (t -> Modifier.isAbstract(t.getModifiers()))
    // 是否是strictfp修饰的类、接口或方法
    , IsStrict (t -> Modifier.isStrict(t.getModifiers()))

    , IsNotPublic (t -> !IsPublic.apply(t))
    , IsNotPrivate (t -> !IsPrivate.apply(t))
    , IsNotProtected (t -> !IsProtected.apply(t))
    , IsNotStatic (t -> !IsStatic.apply(t))
    , IsNotFinal (t -> !IsFinal.apply(t))
    , IsNotSynchronized (t -> !IsSynchronized.apply(t))
    , IsNotVolatile (t -> !IsVolatile.apply(t))
    , IsNotTransient (t -> !IsTransient.apply(t))
    , IsNotNative (t -> !IsNative.apply(t))
    , IsNotInterface (t -> !IsInterface.apply(t))
    , IsNotAbstract (t -> !IsAbstract.apply(t))
    , IsNotStrict (t -> !IsStrict.apply(t))

    // 普通属性或方法，非static && 非final
    , IsOrdinary (t -> IsNotStatic.apply(t) && IsNotFinal.apply(t))

    // 所有getter方法，包含is方法
    , IsGetter (Reflect::isGetter)
    , IsSetter (Reflect::isSetter)

    , IsNotGetter (t -> !IsGetter.apply(t))
    , IsNotSetter (t -> !IsSetter.apply(t))
    ;


    private final Function<Member, Boolean> applier;


    /**
     * 业务作用：把每个枚举常量绑定到具体的成员判定逻辑，使调用方以枚举而非散落的
     * Modifier 位运算表达筛选条件。
     *
     * @param applier 该常量对应的成员判定函数
     * 返回: 无返回值；applier 在常量初始化后不可变。
     */
    Reflect(Function<Member, Boolean> applier) {
        this.applier = applier;
    }


    /**
     * 业务作用：暴露底层判定函数，供需要把条件传给流式 API 或做组合的调用方使用。
     *
     * 参数说明: 无。
     * 返回: 该常量绑定的判定函数。
     */
    public Function<Member, Boolean> getApplier() {
        return applier;
    }


    /**
     * 业务作用：对给定的字段或方法执行本常量代表的判定。
     *
     * @param member 待判定的字段或方法
     * 返回: 满足该条件返回 true，否则返回 false。
     */
    public boolean apply(Member member) {
        return this.applier.apply(member);
    }


    /**
     * 业务作用：判定成员是否为符合 JavaBeans 约定的 getter。四个条件缺一不可：
     * 名称以 get 开头（或返回 boolean 且以 is 开头）、无参、有返回值、public 且非 static。
     * 静态方法与带参方法即使命名相符也不是属性读取器，放行会导致属性拷贝读到错误的值。
     *
     * @param member 待判定的成员
     * 返回: 同时满足全部条件返回 true；非 Method 直接返回 false。
     */
    private static boolean isGetter(Member member) {
        if (!(member instanceof Method method)) {
            return false;
        }
        return (method.getName().startsWith(ReflectUtils.get)
                || (method.getReturnType() == boolean.class && method.getName().startsWith(ReflectUtils.is)))
                && method.getParameterCount() == 0
                && !method.getReturnType().getName().equals("void")
                && IsPublic.apply(member)
                && IsNotStatic.apply(member);
    }


    /**
     * 业务作用：判定成员是否为符合 JavaBeans 约定的 setter。要求名称以 set 开头、
     * 有且仅有一个参数、返回 void、public 且非 static。多参或有返回值的方法即使命名相符
     * 也不是属性写入器，放行会导致属性拷贝调用到业务方法。
     *
     * @param member 待判定的成员
     * 返回: 同时满足全部条件返回 true；非 Method 直接返回 false。
     */
    private static boolean isSetter(Member member) {
        if (!(member instanceof Method method)) {
            return false;
        }
        return method.getName().startsWith(ReflectUtils.set)
                && method.getParameterCount() == 1
                && method.getReturnType().getName().equals("void")
                && IsPublic.apply(member)
                && IsNotStatic.apply(member);
    }

}
