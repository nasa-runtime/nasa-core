package io.github.nasaruntime.core.base;

/**
 * Nasa
 * id自增接口
 */
@SuppressWarnings("unused")
public interface IdGenerate {

    /**
     * 业务作用：生成全局唯一标识。
     *
     * 参数说明: 无。
     * 返回: 唯一标识。
     */
    default long generate() {
        return this.generate(1)[0];
    }

    /**
     * 业务作用：生成全局唯一标识。
     *
     * @param c 源集合
     * 返回: 唯一标识。
     */
    long[] generate(int c);

}
