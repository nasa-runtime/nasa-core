package com.nasa.runtime.core.base;

import com.nasa.runtime.core.annotation.TPS;
import com.nasa.runtime.core.permission.Permissions;
import com.nasa.runtime.core.utils.ColUtils;
import com.nasa.runtime.core.utils.StringUtils;
import lombok.Getter;
import lombok.Setter;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Nasa
 */
@Getter
@Setter
public class AppCollector {

    /* controller级别上RequestMapping指定的路由地址 */
    private final String restfulRouter;
    /* 接口级别上RequestMapping指定的路由地址 */
    private final String interfaceRouter;
    /* 接口全路径 */
    private final String url;
    /* 对象实例 */
    private final Object obj;
    /* 接口方法 */
    private final Method method;
    /**
     * 接口全路径，由/分组所得
     * /abc/df          ==> ["abc", "df"]
     * /abc/{id}        ==> ["abc", "{id}"]
     * /abc/{id}/df     ==> ["abc", "{id}", "df"]
     * /abc/{id}/df/    ==> ["abc", "{id}", "df"]
     */
    private final String[] levels;
    /* 权限注解 */
    private final Permissions permissions;
    /* 接口级别TPS计数 */
    private final TPS tps;

    public AppCollector(String restfulRouter, String interfaceRouter, Object obj, Method method) {
        this.restfulRouter = restfulRouter;
        this.interfaceRouter = interfaceRouter;
        this.url = restfulRouter + interfaceRouter;
        this.obj = obj;
        this.method = method;
        // 如果全路径以/结尾，split会过滤掉最后一个/，数组最后一位不会是空串
        this.levels = ColUtils.slice(this.url.split(StringUtils.Mark_right_slash), 1);

        // REST接口是否注解了@TPS
        this.tps = method.getAnnotation(TPS.class);

        // 方法的注解优先级最高
        Permissions permissions = method.getAnnotation(Permissions.class);
        if (Objects.nonNull(permissions)) {
            this.permissions = permissions;
            return;
        }

        // 其次是类上的注解
        this.permissions = obj.getClass().getAnnotation(Permissions.class);
    }

}
