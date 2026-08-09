package io.github.nasaruntime.core.config;

import io.github.nasaruntime.core.base.KV;
import io.github.nasaruntime.core.utils.ColUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Nasa 优雅停机
 */
@SuppressWarnings("unused")
@Slf4j
public abstract class Graceful {

    /**
     * 业务作用：私有化构造，本类只提供静态的停机钩子注册与执行入口，不允许实例化。
     *
     * 参数说明: 无。
     * 返回: 不对外提供实例。
     */
    private Graceful() {}

    static final List<KV<Integer, Shutdown>> list = new ArrayList<>();
    private static final ReentrantLock lock = new ReentrantLock();

    /**
     * 业务作用：以默认次序 100 注册一个停机钩子，供不关心相对顺序的组件直接使用。
     *
     * @param shutdown 停机时要执行的清理动作
     * 返回: 无返回值；同一实例重复注册只保留第一次。
     */
    public static void registry(Shutdown shutdown) {
        registry(100, shutdown);
    }

    /**
     * 业务作用：注册停机钩子并按 order 升序维持执行次序。次序是必需的：
     * 依赖方必须先于被依赖方停机，否则清理动作会访问到已经关闭的资源。
     * 注册时按实例做去重，避免同一钩子被重复登记导致清理逻辑执行两次。
     *
     * @param order 执行次序，值越小越先执行
     * @param shutdown 停机时要执行的清理动作
     * 返回: 无返回值；该实例已注册过时直接返回，不改变已有次序。
     */
    public static void registry(int order, Shutdown shutdown) {
        lock.lock();
        try {
            for (KV<Integer, Shutdown> kv : list) if (kv.getValue() == shutdown) return;
            list.add(KV.of(order, shutdown));
            ColUtils.ascType(list, KV::getKey);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 业务作用：按注册次序依次执行全部停机钩子。单个钩子抛错只记录日志并继续，
     * 不允许一个组件的清理失败阻断后续组件的清理，否则会留下更多未释放资源。
     *
     * 参数说明: 无。
     * 返回: 无返回值；无论中途是否有钩子失败，都会遍历完整个列表。
     */
    public static void shutdown() {
        for (KV<Integer, Shutdown> kv : list) {
            try {
                kv.getValue().shutdown();
            } catch (Throwable t) {
                log.error(t.getMessage(), t);
            }
        }
    }

    @FunctionalInterface
    public interface Shutdown {

        /**
         * 业务作用：由各组件实现的停机清理动作，在进程退出前释放其持有的资源。
         * 实现应自行处理内部异常并保证幂等，因为无法假定只被调用一次。
         * <p>
         * 在 Spring 应用里用 {@code @Bean} 方法声明本接口的实现时应写成
         * {@code @Bean(destroyMethod = "")}：Spring 的销毁方法推断会把无参的
         * {@code shutdown()} 当作销毁方法，于是容器销毁该 Bean 时会在停机链之外再调用一次，
         * 使清理动作执行两次。用 {@code @Component} 登记的实现不受此影响。
         *
         * 参数说明: 无。
         * 返回: 无返回值；抛出的异常会被调度方捕获记录，不会中断其它钩子。
         */
        void shutdown();
    }
}
