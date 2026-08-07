package io.github.nasaruntime.core.base;

import io.github.nasaruntime.core.concurrent.SyncLock;
import io.github.nasaruntime.core.function.Consumer3;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Nasa
 * 前缀树算法
 */
@SuppressWarnings("all")
@Slf4j
public final class Trie {

    /**
     * 业务作用：私有化构造，本类只提供静态入口，不允许实例化。
     *
     * 参数说明: 无。
     * 返回: 不对外提供实例。
     */
    private Trie() {}

    public static final String DEFAULT = "default";

    /* 场景缓存 */
    static final Map<String, Tree> TREE_MAP = new ConcurrentHashMap<>();

    /**
     * 业务作用：取得指定场景的前缀树，场景不存在时按需创建。不同场景之间词库完全隔离。
     *
     * 参数说明: 无。
     * 返回: 该场景的前缀树。
     */
    public static Tree tree() {
        return tree(DEFAULT);
    }

    /**
     * 业务作用：取得指定场景的前缀树，场景不存在时按需创建。不同场景之间词库完全隔离。
     *
     * @param scene 场景标识，不同场景词库互不影响
     * 返回: 该场景的前缀树。
     */
    public static Tree tree(String scene) {
        return TREE_MAP.computeIfAbsent(scene, Tree::new);
    }

    /**
     * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
     *
     * 参数说明: 无。
     * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
     */
    public static void clear() {
        tree().clear();
    }

    /**
     * 业务作用：配置匹配行为：是否忽略大小写、是否忽略符号、末尾星号是否按通配处理。
     * 配置会影响字符归一化方式，因此构建期与查询期必须一致，否则已入库的词条匹配不上。
     *
     * @param ignoreCase 为 true 时忽略大小写
     * @param ignoreSymbols 为 true 时跳过符号
     * @param lastStar 为 true 时末尾星号按通配处理
     * 返回: 无返回值；配置变更后需重建树才对已有词条生效。
     */
    public static void conf(boolean ignoreCase, boolean ignoreSymbols, boolean lastStar) {
        tree().conf(ignoreCase, ignoreSymbols, lastStar);
    }

    /**
     * 业务作用：加入一条匹配词及其替换词，加入后立即重建并原子发布新树。
     *
     * @param matching 匹配词
     * @param replaced 替换词
     * 返回: 无返回值。
     */
    public static void load(String matching, Object replaced) {
        tree().load(matching, replaced);
    }

    /**
     * 业务作用：移除一条匹配词，移除后立即重建并原子发布新树。
     *
     * @param matching 匹配词
     * 返回: 无返回值；词条不存在时不做任何事。
     */
    public static void remove(String matching) {
        tree().remove(matching);
    }

    /**
     * 业务作用：整体替换词库：先离线构建完整新树再原子发布，读线程要么看到旧全量树、要么看到新全量树，不会撞见半加载状态。
     *
     * @param words 匹配词到替换词的映射
     * 返回: 无返回值；入参为 null 时等价于清空。
     */
    public static void reload(Map<String, ?> words) {
        tree().reload(words);
    }

    /**
     * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
     *
     * 参数说明: 无。
     * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
     */
    public static int size() {
        return tree().size();
    }

    /**
     * 业务作用：判断容器当前是否为空。
     *
     * 参数说明: 无。
     * 返回: 为空返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。不能作为后续操作必定成功的依据。
     */
    public static boolean isEmpty() {
        return tree().isEmpty();
    }

    /**
     * 业务作用：扫描整段文本，把命中的匹配词替换掉并返回过滤后的文本。
     *
     * @param text 待处理文本
     * 返回: 过滤后的文本。
     */
    public static String filter(String text) {
        return tree().filter(text);
    }

    /**
     * 业务作用：扫描整段文本，把命中的匹配词替换掉并返回过滤后的文本。
     *
     * @param text 待处理文本
     * @param consumer 见上述说明
     * 返回: 过滤后的文本。
     */
    public static void filter(String text, Consumer<Character> consumer) {
        tree().filter(text, consumer);
    }

    /**
     * 业务作用：判断文本中是否命中任一匹配词。
     *
     * @param text 待处理文本
     * @param consumer 见上述说明
     * 返回: 命中返回 true。
     */
    public static <T> void matching(String text, Consumer3<String, String, T> consumer) {
        tree().matching(text, consumer);
    }

    /**
     * 业务作用：匹配文本并取出对应的替换词。
     *
     * @param text 待处理文本
     * @param dft 未命中时返回的默认值
     * 返回: 替换词；未命中时返回给定默认值。
     */
    public static <T> T matchingGet(String text, Supplier<T> dft) {
        return tree().matchingGet(text, dft);
    }

    /**
     * 业务作用：匹配文本并取出对应的替换词。
     *
     * @param text 待处理文本
     * @param dft 未命中时返回的默认值
     * 返回: 替换词；未命中时返回给定默认值。
     */
    public static <T> T matchingGet(String text, T dft) {
        return tree().matchingGet(text, dft);
    }

    /**
     * 业务作用：从每个起点尝试匹配，判断文本中是否含有任一词条。
     *
     * @param text 待处理文本
     * 返回: 至少命中一处返回 true。
     */
    public static boolean contains(String text) {
        return tree().contains(text);
    }

    /**
     * 业务作用：要求整段文本被某个词条或末尾通配完整消费。
     *
     * @param text 待处理文本
     * 返回: 完整匹配返回 true。
     */
    public static boolean containsExact(String text) {
        return tree().containsExact(text);
    }

    /**
     * 业务作用：要求整段文本被词条或末尾通配完整消费，命中才取替换值。
     * 刻意不复用短词优先的匹配：那会让 abc 被 ab 提前匹配而错判为整段命中。
     *
     * @param text 待处理文本
     * @param dft 未命中时返回的默认值
     * 返回: 替换值；未完整命中时返回默认值。
     */
    public static <T> T exactGet(String text, Supplier<T> dft) {
        return tree().exactGet(text, dft);
    }

    /**
     * 业务作用：要求整段文本被词条或末尾通配完整消费，命中才取替换值。
     * 刻意不复用短词优先的匹配：那会让 abc 被 ab 提前匹配而错判为整段命中。
     *
     * @param text 待处理文本
     * @param dft 未命中时返回的默认值
     * 返回: 替换值；未完整命中时返回默认值。
     */
    public static <T> T exactGet(String text, T dft) {
        return tree().exactGet(text, dft);
    }

    /**
     * 业务作用：从文本开头做短词优先的前缀匹配。
     *
     * @param text 待处理文本
     * @param dft 未命中时返回的默认值
     * 返回: 命中词条的替换值；未命中返回默认值。
     */
    public static <T> T prefixGet(String text, Supplier<T> dft) {
        return tree().prefixGet(text, dft);
    }

    /**
     * 业务作用：从文本开头做短词优先的前缀匹配。
     *
     * @param text 待处理文本
     * @param dft 未命中时返回的默认值
     * 返回: 命中词条的替换值；未命中返回默认值。
     */
    public static <T> T prefixGet(String text, T dft) {
        return tree().prefixGet(text, dft);
    }

    /**
     * 业务作用：从文本开头做最长前缀匹配，优先取能吃掉更多文本的词条。
     *
     * @param text 待处理文本
     * @param dft 未命中时返回的默认值
     * 返回: 命中词条的替换值；未命中返回默认值。
     */
    public static <T> T longestPrefixGet(String text, Supplier<T> dft) {
        return tree().longestPrefixGet(text, dft);
    }

    /**
     * 业务作用：从文本开头做最长前缀匹配，优先取能吃掉更多文本的词条。
     *
     * @param text 待处理文本
     * @param dft 未命中时返回的默认值
     * 返回: 命中词条的替换值；未命中返回默认值。
     */
    public static <T> T longestPrefixGet(String text, T dft) {
        return tree().longestPrefixGet(text, dft);
    }

    /**
     * 业务作用：判断词库中是否存在以给定前缀开头的词条。比列出全部词条更省，适合只需判断有无的场景。
     *
     * @param prefix 词条前缀
     * 返回: 存在返回 true。
     */
    public static boolean hasPrefix(String prefix) {
        return tree().hasPrefix(prefix);
    }

    /**
     * 业务作用：列出词库中以给定前缀开头的词条，供自动补全使用。深度优先收集，顺序不保证。
     *
     * @param prefix 词条前缀
     * 返回: 命中的词条列表；前缀为 null 或数量上限不为正时返回空列表。
     */
    public static List<String> wordsWithPrefix(String prefix) {
        return tree().wordsWithPrefix(prefix);
    }

    /**
     * 业务作用：列出词库中以给定前缀开头的词条，供自动补全使用。深度优先收集，顺序不保证。
     *
     * @param prefix 词条前缀
     * @param limit 最大返回数量
     * 返回: 命中的词条列表；前缀为 null 或数量上限不为正时返回空列表。
     */
    public static List<String> wordsWithPrefix(String prefix, int limit) {
        return tree().wordsWithPrefix(prefix, limit);
    }

    public static class Tree {

        private static final Object DEFAULT = new Object();

        /* 场景 */
        private final String scene;
        /* 待生效配置: 是否忽略大小写, 由 conf 设置, 下次重建快照时烘焙进 State */
        private boolean ignoreCase = true;
        /* 待生效配置: 是否忽略特殊符号 */
        private boolean ignoreSymbols = true;
        /* 待生效配置: 是否支持匹配词最后一位*号匹配 */
        private boolean lastStar = false;
        /* 词条源 (matching -> replaced), 重建快照的唯一依据; 仅在写锁内访问 */
        private final Map<String, Object> entries = new LinkedHashMap<>();
        /* 已发布的不可变快照; 读路径只读这个 volatile 引用, 无锁、无竞争、无半加载窗口 */
        private volatile State state = new State(new Node((char) 0), true, true, false, 0);

        /**
         * 业务作用：构造指定场景的前缀树。
         *
         * @param scene 场景标识，不同场景词库互不影响
         * 返回: 该场景的前缀树实例。
         */
        Tree(String scene) {
            this.scene = scene;
        }

        /**
         * 业务作用：配置匹配行为：是否忽略大小写、是否忽略符号、末尾星号是否按通配处理。
         * 配置会影响字符归一化方式，因此构建期与查询期必须一致，否则已入库的词条匹配不上。
         *
         * @param ignoreCase 为 true 时忽略大小写
         * @param ignoreSymbols 为 true 时跳过符号
         * @param lastStar 为 true 时末尾星号按通配处理
         * 返回: 无返回值；配置变更后需重建树才对已有词条生效。
         */
        public void conf(boolean ignoreCase, boolean ignoreSymbols, boolean lastStar) {
            SyncLock.lock("Trie.Tree", 1, "Trie.Tree", () -> {
                this.ignoreCase = ignoreCase;
                this.ignoreSymbols = ignoreSymbols;
                this.lastStar = lastStar;
                // 配置变化会影响构建期归一化 (ignoreCase) 与查询语义, 按当前词条重建并发布一致快照
                this.publish();
            });
        }

        /**
         * 业务作用：移除全部元素，解除容器对它们的强引用后交给 GC。
         *
         * 参数说明: 无。
         * 返回: 无返回值；并发写入仍在进行时不保证返回后容器为空。
         */
        public void clear() {
            SyncLock.lock("Trie.Tree", 1, "Trie.Tree", () -> {
                this.entries.clear();
                this.publish();
                log.info("Trie's Tree[{}] clear matching words", this.scene);
            });
        }

        /**
         * 业务作用：加入一条匹配词及其替换词，加入后立即重建并原子发布新树。
         *
         * @param matching 匹配词
         * @param replaced 替换词
         * 返回: 无返回值。
         */
        public void load(String matching, Object replaced) {
            if (matching == null || matching.isEmpty()) {
                // 非法匹配词直接跳过, 不写入 entries, 避免污染后续重建
                log.warn("Trie's Tree[{}] skip null/empty matching word", this.scene);
                return;
            }
            SyncLock.lock("Trie.Tree", 1, "Trie.Tree", () -> {
                this.entries.put(matching, replaced);
                this.publish();
                if (!(replaced instanceof String)) {
                    log.info("Trie's Tree[{}] loading matching words: {}", this.scene, matching);
                    return;
                }
                log.info("Trie's Tree[{}] loading matching words: {}, replaced: {}", this.scene, matching, replaced);
            });
        }

        /**
         * 业务作用：移除一条匹配词，移除后立即重建并原子发布新树。
         *
         * @param matching 匹配词
         * 返回: 无返回值；词条不存在时不做任何事。
         */
        public void remove(String matching) {
            if (matching == null || matching.isEmpty()) return;
            SyncLock.lock("Trie.Tree", 1, "Trie.Tree", () -> {
                if (!this.entries.containsKey(matching)) return;
                this.entries.remove(matching);
                this.publish();
                log.info("Trie's Tree[{}] remove matching words: {}", this.scene, matching);
            });
        }

        /**
         * 业务作用：整体替换词库：先离线构建完整新树再原子发布，读线程要么看到旧全量树、要么看到新全量树，不会撞见半加载状态。
         *
         * @param words 匹配词到替换词的映射
         * 返回: 无返回值；入参为 null 时等价于清空。
         */
        public void reload(Map<String, ?> words) {
            SyncLock.lock("Trie.Tree", 1, "Trie.Tree", () -> {
                this.entries.clear();
                if (words != null) {
                    // 跳过 null/空 key, 坏数据不进 entries, 不影响其余词条
                    words.forEach((k, v) -> {
                        if (k != null && !k.isEmpty()) this.entries.put(k, v);
                    });
                }
                this.publish();
                log.info("Trie's Tree[{}] reload matching words: {}", this.scene, this.entries.size());
            });
        }

        /**
         * 业务作用：按当前词条与配置离线构建一棵新树，再原子发布到 volatile 状态。
         * 必须离线构建：直接在读线程可见的树上增删会让匹配过程读到半成品。仅允许在写锁内调用。
         *
         * 参数说明: 无。
         * 返回: 无返回值；发布后新树对所有读线程立即可见。
         */
        private void publish() {
            Node root = new Node((char) 0);
            for (Map.Entry<String, Object> e : this.entries.entrySet()) {
                String matching = e.getKey();
                // 防御: 非法词不参与构建 (正常情况下 load/reload 已拦截, 这里兜底防 NPE)
                if (matching == null || matching.isEmpty()) continue;
                Node node = root;
                // 累积路径上各节点的 me (首次创建时的原始字符), 作为 end 节点的匹配词, 免去 matching 时父指针回溯
                StringBuilder word = new StringBuilder();
                for (char c : matching.toCharArray()) {
                    // 构建期归一化: 大写转小写 (与查询期 normalizeCase 必须用同一份 ignoreCase, 否则匹配不上)
                    char key = Trie.normalizeCase(c, this.ignoreCase);
                    Node child = node.children.get(key);
                    if (child == null) {
                        child = new Node(c);
                        node.children.put(key, child);
                    }
                    word.append(child.me);
                    node = child;
                }
                node.end = true;
                node.word = word.toString();
                // 预存 replaced 的字符序列, filter 命中时零分配输出 (免每次 toString().toCharArray())
                Object replaced = e.getValue();
                node.replacedChars = replaced == null ? null : replaced.toString().toCharArray();
                node.replaced = replaced;
            }
            // size 取 entries 条目数 (load/reload 已过滤 null/空 key); 按原始 key 计数, 大小写变体算多条, 不去重
            this.state = new State(root, this.ignoreCase, this.ignoreSymbols, this.lastStar, this.entries.size());
        }

        /**
         * 业务作用：报告当前元素个数，供容量观测与遍历前的预分配。
         *
         * 参数说明: 无。
         * 返回: 元素个数；并发下为弱一致结果，仅反映采样瞬间的状态。
         */
        public int size() {
            return this.state.size;
        }

        /**
         * 业务作用：判断容器当前是否为空。
         *
         * 参数说明: 无。
         * 返回: 为空返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。不能作为后续操作必定成功的依据。
         */
        public boolean isEmpty() {
            return this.state.size == 0;
        }

        /**
         * 业务作用：扫描整段文本，把命中的匹配词替换掉并返回过滤后的文本。
         *
         * @param text 待处理文本
         * 返回: 过滤后的文本。
         */
        public String filter(String text) {
            Objects.requireNonNull(text, "text");
            StringBuilder sb = new StringBuilder(text.length());
            this.filter(text, sb::append);
            return sb.toString();
        }

        /**
         * 业务作用：扫描整段文本，把命中的匹配词替换掉并返回过滤后的文本。
         *
         * @param text 待处理文本
         * @param consumer 见上述说明
         * 返回: 过滤后的文本。
         */
        public void filter(String text, Consumer<Character> consumer) {
            Objects.requireNonNull(text, "text");
            this.state.filter(text, consumer);
        }

        /**
         * 业务作用：判断文本中是否命中任一匹配词。
         *
         * @param text 待处理文本
         * @param consumer 见上述说明
         * 返回: 命中返回 true。
         */
        public <T> void matching(String text, Consumer3<String, String, T> consumer) {
            Objects.requireNonNull(text, "text");
            this.state.matching(text, consumer);
        }

        /**
         * 业务作用：匹配文本并取出对应的替换词。
         *
         * @param text 待处理文本
         * @param dft 未命中时返回的默认值
         * 返回: 替换词；未命中时返回给定默认值。
         */
        public <T> T matchingGet(String text, Supplier<T> dft) {
            Objects.requireNonNull(text, "text");
            Object o = this.state.matchingGet(text, DEFAULT);
            return o == DEFAULT ? dft.get() : (T) o;
        }

        /**
         * 业务作用：匹配文本并取出对应的替换词。
         *
         * @param text 待处理文本
         * @param dft 未命中时返回的默认值
         * 返回: 替换词；未命中时返回给定默认值。
         */
        public <T> T matchingGet(String text, T dft) {
            Objects.requireNonNull(text, "text");
            return this.state.matchingGet(text, dft);
        }

        /**
         * 业务作用：从每个起点尝试匹配，判断文本中是否含有任一词条。
         *
         * @param text 待处理文本
         * 返回: 至少命中一处返回 true。
         */
        public boolean contains(String text) {
            Objects.requireNonNull(text, "text");
            return this.state.contains(text);
        }

        /**
         * 业务作用：要求整段文本被某个词条或末尾通配完整消费。
         *
         * @param text 待处理文本
         * 返回: 完整匹配返回 true。
         */
        public boolean containsExact(String text) {
            Objects.requireNonNull(text, "text");
            return this.state.exactGet(text, DEFAULT) != DEFAULT;
        }

        /**
         * 业务作用：要求整段文本被词条或末尾通配完整消费，命中才取替换值。
         * 刻意不复用短词优先的匹配：那会让 abc 被 ab 提前匹配而错判为整段命中。
         *
         * @param text 待处理文本
         * @param dft 未命中时返回的默认值
         * 返回: 替换值；未完整命中时返回默认值。
         */
        public <T> T exactGet(String text, Supplier<T> dft) {
            Objects.requireNonNull(text, "text");
            Object o = this.state.exactGet(text, DEFAULT);
            return o == DEFAULT ? dft.get() : (T) o;
        }

        /**
         * 业务作用：要求整段文本被词条或末尾通配完整消费，命中才取替换值。
         * 刻意不复用短词优先的匹配：那会让 abc 被 ab 提前匹配而错判为整段命中。
         *
         * @param text 待处理文本
         * @param dft 未命中时返回的默认值
         * 返回: 替换值；未完整命中时返回默认值。
         */
        public <T> T exactGet(String text, T dft) {
            Objects.requireNonNull(text, "text");
            return this.state.exactGet(text, dft);
        }

        /**
         * 业务作用：从文本开头做短词优先的前缀匹配。
         *
         * @param text 待处理文本
         * @param dft 未命中时返回的默认值
         * 返回: 命中词条的替换值；未命中返回默认值。
         */
        public <T> T prefixGet(String text, Supplier<T> dft) {
            Objects.requireNonNull(text, "text");
            Object o = this.state.prefixGet(text, DEFAULT);
            return o == DEFAULT ? dft.get() : (T) o;
        }

        /**
         * 业务作用：从文本开头做短词优先的前缀匹配。
         *
         * @param text 待处理文本
         * @param dft 未命中时返回的默认值
         * 返回: 命中词条的替换值；未命中返回默认值。
         */
        public <T> T prefixGet(String text, T dft) {
            Objects.requireNonNull(text, "text");
            return this.state.prefixGet(text, dft);
        }

        /**
         * 业务作用：从文本开头做最长前缀匹配，优先取能吃掉更多文本的词条。
         *
         * @param text 待处理文本
         * @param dft 未命中时返回的默认值
         * 返回: 命中词条的替换值；未命中返回默认值。
         */
        public <T> T longestPrefixGet(String text, Supplier<T> dft) {
            Objects.requireNonNull(text, "text");
            Object o = this.state.longestPrefixGet(text, DEFAULT);
            return o == DEFAULT ? dft.get() : (T) o;
        }

        /**
         * 业务作用：从文本开头做最长前缀匹配，优先取能吃掉更多文本的词条。
         *
         * @param text 待处理文本
         * @param dft 未命中时返回的默认值
         * 返回: 命中词条的替换值；未命中返回默认值。
         */
        public <T> T longestPrefixGet(String text, T dft) {
            Objects.requireNonNull(text, "text");
            return this.state.longestPrefixGet(text, dft);
        }

        /**
         * 业务作用：判断词库中是否存在以给定前缀开头的词条。比列出全部词条更省，适合只需判断有无的场景。
         *
         * @param prefix 词条前缀
         * 返回: 存在返回 true。
         */
        public boolean hasPrefix(String prefix) {
            return this.state.hasPrefix(prefix);
        }

        /**
         * 业务作用：列出词库中以给定前缀开头的词条，供自动补全使用。深度优先收集，顺序不保证。
         *
         * @param prefix 词条前缀
         * 返回: 命中的词条列表；前缀为 null 或数量上限不为正时返回空列表。
         */
        public List<String> wordsWithPrefix(String prefix) {
            return this.state.wordsWithPrefix(prefix, Integer.MAX_VALUE);
        }

        /**
         * 业务作用：列出词库中以给定前缀开头的词条，供自动补全使用。深度优先收集，顺序不保证。
         *
         * @param prefix 词条前缀
         * @param limit 最大返回数量
         * 返回: 命中的词条列表；前缀为 null 或数量上限不为正时返回空列表。
         */
        public List<String> wordsWithPrefix(String prefix, int limit) {
            return this.state.wordsWithPrefix(prefix, limit);
        }
    }

    /**
     * 业务作用：构建期与查询期共用的大小写归一化：忽略大小写时把大写转成小写。
     * 两期必须调用同一实现并传入同一份配置，否则同一个字符会落到不同分支而匹配不上。
     *
     * @param c 字符
     * @param ignoreCase 为 true 时忽略大小写
     * 返回: 归一化后的字符。
     */
    private static char normalizeCase(char c, boolean ignoreCase) {
        return ignoreCase && c >= 65 && c <= 90 ? (char) (c | 32) : c;
    }

    /**
     * 不可变前缀树快照: 根节点 + 该快照构建时烘焙的配置. 发布后只读, 读线程无锁安全访问.
     */
    static class State {

        private final Node root;
        private final boolean ignoreCase;
        private final boolean ignoreSymbols;
        private final boolean lastStar;
        private final int size;

        /**
         * 业务作用：构造一个不可变的树快照，绑定构建时的根节点与配置。
         * 配置随快照一起冻结，使查询期的归一化方式必然与构建期一致。
         *
         * @param root 根节点
         * @param ignoreCase 为 true 时忽略大小写
         * @param ignoreSymbols 为 true 时跳过符号
         * @param lastStar 为 true 时末尾星号按通配处理
         * @param size 见上述说明
         * 返回: 只读快照；发布后读线程可无锁访问。
         */
        State(Node root, boolean ignoreCase, boolean ignoreSymbols, boolean lastStar, int size) {
            this.root = root;
            this.ignoreCase = ignoreCase;
            this.ignoreSymbols = ignoreSymbols;
            this.lastStar = lastStar;
            this.size = size;
        }

        /**
         * 业务作用：判断字符是否属于匹配时需要跳过的符号，用于让 a-b-c 也能命中 abc。
         *
         * @param c 字符
         * 返回: 是可跳过符号时返回 true。
         */
        private boolean ignoreSymbols(char c) {
            // 忽略特殊符号
            return this.ignoreSymbols && ((c >= 32 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96) || (c >= 123 && c <= 126));
        }

        /**
         * 业务作用：从指定位置起做一次最长匹配。
         *
         * @param text 待处理文本
         * @param from 起始下标
         * @param endExclusive 见上述说明
         * 返回: 匹配到的终结节点；未命中返回 null。
         */
        private Node match(String text, int from, int[] endExclusive) {
            Node node = this.root;
            // 最近祖先的终止 '*' 通配节点, 精确路径失败时回退
            Node star = null;
            for (int j = from; j < text.length(); j++) {
                char c = text.charAt(j);
                if (this.ignoreSymbols(c)) {
                    // 起点就是符号则不匹配, 词内部符号跳过
                    if (node == this.root) break;
                    continue;
                }
                if (this.lastStar) {
                    // 进入精确分支前, 先记下当前层的终止 '*' 通配候选
                    Node sc = node.children.get('*');
                    if (sc != null && sc.end) star = sc;
                }
                node = node.children.get(Trie.normalizeCase(c, this.ignoreCase));
                if (Objects.isNull(node)) break;
                if (node.end) {
                    // 精确命中, 精确优先于通配
                    endExclusive[0] = j + 1;
                    return node;
                }
            }
            // 精确未命中: 回退最近的 '*' 通配候选 (通配吃掉剩余文本)
            if (star == null) return null;
            endExclusive[0] = text.length();
            return star;
        }

        /**
         * 业务作用：从指定位置起找最长前缀。精确匹配到文本末尾优先于通配；否则末尾星号按吃完整段剩余文本处理。
         *
         * @param text 待处理文本
         * @param from 起始下标
         * @param endExclusive 见上述说明
         * 返回: 匹配到的终结节点；未命中返回 null。
         */
        private Node longestMatch(String text, int from, int[] endExclusive) {
            Node node = this.root;
            Node best = null;
            int bestEnd = from;
            Node star = null;
            for (int j = from; j < text.length(); j++) {
                char c = text.charAt(j);
                if (this.ignoreSymbols(c)) {
                    if (node == this.root) break;
                    continue;
                }
                if (this.lastStar) {
                    Node sc = node.children.get('*');
                    if (sc != null && sc.end) star = sc;
                }
                node = node.children.get(Trie.normalizeCase(c, this.ignoreCase));
                if (Objects.isNull(node)) break;
                if (node.end) {
                    best = node;
                    bestEnd = j + 1;
                }
            }
            if (best != null && bestEnd == text.length()) {
                endExclusive[0] = bestEnd;
                return best;
            }
            if (star != null) {
                endExclusive[0] = text.length();
                return star;
            }
            if (best == null) return null;
            endExclusive[0] = bestEnd;
            return best;
        }

        /**
         * 业务作用：要求整段文本被完整消费。刻意不复用 match：短词优先会让 abc 被 ab 提前匹配掉，从而把部分匹配错判成整段命中。
         *
         * @param text 待处理文本
         * 返回: 匹配到的终结节点；未完整消费时返回 null。
         */
        private Node exactMatch(String text) {
            Node node = this.root;
            Node star = null;
            for (int j = 0; j < text.length(); j++) {
                char c = text.charAt(j);
                if (this.ignoreSymbols(c)) {
                    if (node == this.root) return null;
                    continue;
                }
                if (this.lastStar) {
                    Node sc = node.children.get('*');
                    if (sc != null && sc.end) star = sc;
                }
                node = node.children.get(Trie.normalizeCase(c, this.ignoreCase));
                if (Objects.isNull(node)) return star;
            }
            return node.end ? node : star;
        }

        /**
         * 业务作用：沿前缀逐字符下钻，定位前缀对应的子树根节点。
         *
         * @param prefix 词条前缀
         * 返回: 子树根节点；前缀不存在时返回 null。
         */
        private Node prefixNode(String prefix) {
            if (prefix == null) return null;
            Node node = this.root;
            for (int i = 0; i < prefix.length(); i++) {
                node = node.children.get(Trie.normalizeCase(prefix.charAt(i), this.ignoreCase));
                if (node == null) return null;
            }
            return node;
        }

        /**
         * 业务作用：从给定节点开始深度优先收集词条，达到数量上限即停止，避免大前缀下的全量遍历。
         *
         * @param node 起始节点
         * @param result 见上述说明
         * @param limit 最大返回数量
         * 返回: 无返回值；结果写入传入的收集器。
         */
        private void collectWords(Node node, List<String> result, int limit) {
            if (result.size() >= limit) return;
            if (node.end) result.add(node.word);
            if (result.size() >= limit) return;
            node.children.forEach(child -> this.collectWords(child, result, limit));
        }

        /**
         * 业务作用：扫描整段文本，把命中的匹配词替换掉并返回过滤后的文本。
         *
         * @param text 待处理文本
         * @param consumer 见上述说明
         * 返回: 过滤后的文本。
         */
        void filter(String text, Consumer<Character> consumer) {
            if (this.root.children.isEmpty()) {
                // 空树 (无匹配词) 原样输出, 不能清空整段文本
                for (int i = 0; i < text.length(); i++) consumer.accept(text.charAt(i));
                return;
            }
            int[] end = new int[1];
            for (int i = 0; i < text.length(); i++) {
                Node hit = this.match(text, i, end);
                if (hit == null) {
                    // 当前位置不是任何匹配词的起点, 原样输出
                    consumer.accept(text.charAt(i));
                    continue;
                }
                // 命中, 输出替换词 (replacedChars 已在构建期预存, 零分配)
                if (hit.replacedChars != null) {
                    for (char r : hit.replacedChars) consumer.accept(r);
                }
                i = end[0] - 1;
            }
        }

        /**
         * 业务作用：判断文本中是否命中任一匹配词。
         *
         * @param text 待处理文本
         * @param consumer 见上述说明
         * 返回: 命中返回 true。
         */
        <T> void matching(String text, Consumer3<String, String, T> consumer) {
            if (this.root.children.isEmpty()) return;
            int[] end = new int[1];
            for (int i = 0; i < text.length(); i++) {
                Node hit = this.match(text, i, end);
                if (hit == null) continue;
                // 命中, 匹配词在构建期已存入 end 节点, 免父指针回溯重建
                i = end[0] - 1;
                consumer.accept(text, hit.word, (T) hit.replaced);
            }
        }

        /**
         * 业务作用：从每个起点尝试匹配，判断文本中是否含有任一词条。
         *
         * @param text 待处理文本
         * 返回: 至少命中一处返回 true。
         */
        boolean contains(String text) {
            if (this.root.children.isEmpty()) return false;
            int[] end = new int[1];
            for (int i = 0; i < text.length(); i++) {
                if (this.match(text, i, end) != null) return true;
            }
            return false;
        }

        /**
         * 业务作用：匹配文本并取出对应的替换词。
         *
         * @param text 待处理文本
         * @param dft 未命中时返回的默认值
         * 返回: 替换词；未命中时返回给定默认值。
         */
        <T> T matchingGet(String text, T dft) {
            if (this.root.children.isEmpty()) return dft;
            int[] end = new int[1];
            for (int i = 0; i < text.length(); i++) {
                Node hit = this.match(text, i, end);
                if (hit != null) return (T) hit.replaced;
            }
            return dft;
        }

        /**
         * 业务作用：要求整段文本被词条或末尾通配完整消费，命中才取替换值。
         * 刻意不复用短词优先的匹配：那会让 abc 被 ab 提前匹配而错判为整段命中。
         *
         * @param text 待处理文本
         * @param dft 未命中时返回的默认值
         * 返回: 替换值；未完整命中时返回默认值。
         */
        <T> T exactGet(String text, T dft) {
            if (this.root.children.isEmpty()) return dft;
            Node hit = this.exactMatch(text);
            return hit == null ? dft : (T) hit.replaced;
        }

        /**
         * 业务作用：从文本开头做短词优先的前缀匹配。
         *
         * @param text 待处理文本
         * @param dft 未命中时返回的默认值
         * 返回: 命中词条的替换值；未命中返回默认值。
         */
        <T> T prefixGet(String text, T dft) {
            if (this.root.children.isEmpty()) return dft;
            int[] end = new int[1];
            Node hit = this.match(text, 0, end);
            return hit == null ? dft : (T) hit.replaced;
        }

        /**
         * 业务作用：从文本开头做最长前缀匹配，优先取能吃掉更多文本的词条。
         *
         * @param text 待处理文本
         * @param dft 未命中时返回的默认值
         * 返回: 命中词条的替换值；未命中返回默认值。
         */
        <T> T longestPrefixGet(String text, T dft) {
            if (this.root.children.isEmpty()) return dft;
            int[] end = new int[1];
            Node hit = this.longestMatch(text, 0, end);
            return hit == null ? dft : (T) hit.replaced;
        }

        /**
         * 业务作用：判断词库中是否存在以给定前缀开头的词条。比列出全部词条更省，适合只需判断有无的场景。
         *
         * @param prefix 词条前缀
         * 返回: 存在返回 true。
         */
        boolean hasPrefix(String prefix) {
            if (this.root.children.isEmpty()) return false;
            Node node = this.prefixNode(prefix);
            return node != null && (node != this.root || !node.children.isEmpty());
        }

        /**
         * 业务作用：列出词库中以给定前缀开头的词条，供自动补全使用。深度优先收集，顺序不保证。
         *
         * @param prefix 词条前缀
         * @param limit 最大返回数量
         * 返回: 命中的词条列表；前缀为 null 或数量上限不为正时返回空列表。
         */
        List<String> wordsWithPrefix(String prefix, int limit) {
            List<String> result = new ArrayList<>();
            if (this.root.children.isEmpty() || limit <= 0) return result;
            Node node = this.prefixNode(prefix);
            if (node == null) return result;
            this.collectWords(node, result, limit);
            return result;
        }
    }

    static class Node {
        /* 本节点字符 (原始大小写); 根节点为 '\0'; 仅构建期拼匹配词用 */
        final char me;
        /* 子节点 (原始 char 键, 无装箱) */
        final CharNodeMap children = new CharNodeMap();
        /* 是否匹配词结尾 */
        boolean end = false;
        /* 替换词 (matchingGet 原样返回) */
        Object replaced;
        /* replaced.toString() 的字符缓存, filter 命中时零分配输出 */
        char[] replacedChars;
        /* 匹配词 (仅 end 节点非空), matching 回调直接取, 免父指针回溯 */
        String word;

        /**
         * 业务作用：构造一个前缀树节点并记录其原始字符。记录原始字符而非归一化后的字符，使命中时能还原出词条的本来面目。
         *
         * @param me 该节点的原始字符
         * 返回: 新建的节点。
         */
        Node(char me) {
            this.me = me;
        }
    }

    /**
     * 原始 {@code char} 键的子节点表: 开放寻址 + 线性探测, 消除 {@code HashMap<Character, Node>}
     * 查询时的 {@code Character} 装箱 (中文字符 >127 不在 Character 缓存区, 每次查询每字都会新建对象).
     * <p>
     * 首次 put 才分配数组, 空叶子节点零数组开销. 构建期 (写锁内) put, 发布后只读 get, 读路径无锁安全.
     */
    static final class CharNodeMap {

        /* 键 (char) 与值 (Node) 平行数组; null 值槽即空槽, 故 '\0' 也能作合法键 */
        private char[] keys;
        private Node[] vals;
        private int size;
        /* 容量-1, 容量恒为 2 的幂, 用位与取桶 */
        private int mask;

        /**
         * 业务作用：按归一化后的字符取子节点。
         *
         * @param c 字符
         * 返回: 子节点；不存在时返回 null。
         */
        Node get(char c) {
            Node[] vals = this.vals;
            if (vals == null) return null;
            int mask = this.mask;
            char[] keys = this.keys;
            int i = c & mask;
            for (; ; ) {
                Node v = vals[i];
                if (v == null) return null;
                if (keys[i] == c) return v;
                i = (i + 1) & mask;
            }
        }

        /**
         * 业务作用：挂入子节点，容量不足时先扩容。
         *
         * @param c 字符
         * @param v 字段值
         * 返回: 无返回值。
         */
        void put(char c, Node v) {
            if (this.vals == null) {
                this.keys = new char[4];
                this.vals = new Node[4];
                this.mask = 3;
            } else if ((this.size + 1) * 5 >= (this.mask + 1) * 3) {
                // 负载因子超过 0.6 先扩容, 控制探测链长度
                this.resize();
            }
            this.insert(c, v);
        }

        /**
         * 业务作用：把子节点写入当前节点的子表。
         *
         * @param c 字符
         * @param v 字段值
         * 返回: 无返回值。
         */
        private void insert(char c, Node v) {
            char[] keys = this.keys;
            Node[] vals = this.vals;
            int i = c & this.mask;
            for (; ; ) {
                if (vals[i] == null) {
                    keys[i] = c;
                    vals[i] = v;
                    this.size++;
                    return;
                }
                if (keys[i] == c) {
                    vals[i] = v;
                    return;
                }
                i = (i + 1) & this.mask;
            }
        }

        /**
         * 业务作用：扩容子节点表并重新散列。倍增扩容把多次插入的成本摊薄成常数。
         *
         * 参数说明: 无。
         * 返回: 无返回值。
         */
        private void resize() {
            char[] oldKeys = this.keys;
            Node[] oldVals = this.vals;
            int newCap = (this.mask + 1) << 1;
            this.keys = new char[newCap];
            this.vals = new Node[newCap];
            this.mask = newCap - 1;
            this.size = 0;
            for (int i = 0; i < oldVals.length; i++) {
                if (oldVals[i] != null) this.insert(oldKeys[i], oldVals[i]);
            }
        }

        /**
         * 业务作用：判断容器当前是否为空。
         *
         * 参数说明: 无。
         * 返回: 为空返回 true；并发下为弱一致结果，仅反映采样瞬间的状态。不能作为后续操作必定成功的依据。
         */
        boolean isEmpty() {
            return this.size == 0;
        }

        /**
         * 业务作用：遍历当前元素并逐个交给回调，遍历不阻塞并发读写。
         *
         * @param consumer 见方法语义
         * 返回: 无返回值；回调抛出的异常直接向上传播，遍历随即中断。
         */
        void forEach(Consumer<Node> consumer) {
            Node[] vals = this.vals;
            if (vals == null) return;
            for (Node v : vals) {
                if (v != null) consumer.accept(v);
            }
        }
    }
}
