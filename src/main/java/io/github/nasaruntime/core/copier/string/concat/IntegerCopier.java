package io.github.nasaruntime.core.copier.string.concat;


/**
 * Nasa
 * NumberCopier缓存整数数字长额
 */
@SuppressWarnings("unused")
public class IntegerCopier extends NumberCopier {

    /**
     * 业务作用：给出本复制器在注册表中的类型键，Integer 类型的源数据据此路由到本实现。
     * 该键必须与源数据 {@code getClass().getSimpleName()} 一致，否则注册后永远匹配不上。
     *
     * 参数说明: 无。
     * 返回: 类型键 Integer。
     */
    @Override
    public String copier() {
        return "Integer";
    }
}
