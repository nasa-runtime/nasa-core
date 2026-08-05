package com.nasa.runtime.core.base;

/**
 * Nasa
 * id自增接口
 */
@SuppressWarnings("unused")
public interface IdGenerate {

    /**
     * 生成自增ID
     */
    default long generate() {
        return this.generate(1)[0];
    }

    /**
     * 生成自增ID
     * @param c 生成的ID数
     */
    long[] generate(int c);

}
