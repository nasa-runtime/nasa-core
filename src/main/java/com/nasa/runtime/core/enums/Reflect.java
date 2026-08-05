package com.nasa.runtime.core.enums;

import com.nasa.runtime.core.utils.ReflectUtils;

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


    Reflect(Function<Member, Boolean> applier) {
        this.applier = applier;
    }


    public Function<Member, Boolean> getApplier() {
        return applier;
    }


    /**
     * 执行校验
     * @param member 属性 或 方法
     */
    public boolean apply(Member member) {
        return this.applier.apply(member);
    }


    /**
     * getter没有参数，且有返回值，是public，非static
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
     * setter有且仅有1个参数，没有返回值，是public，非static
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
