package com.nasa.runtime.core.base;

import com.nasa.runtime.core.concurrent.SyncLock;
import com.nasa.runtime.core.function.Consumer3;
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

    private Trie() {}

    public static final String DEFAULT = "default";

    /* 场景缓存 */
    static final Map<String, Tree> TREE_MAP = new ConcurrentHashMap<>();

    public static Tree tree() {
        return tree(DEFAULT);
    }

    public static Tree tree(String scene) {
        return TREE_MAP.computeIfAbsent(scene, Tree::new);
    }

    public static void clear() {
        tree().clear();
    }

    public static void conf(boolean ignoreCase, boolean ignoreSymbols, boolean lastStar) {
        tree().conf(ignoreCase, ignoreSymbols, lastStar);
    }

    public static void load(String matching, Object replaced) {
        tree().load(matching, replaced);
    }

    /**
     * 从默认 scene 移除一个匹配词.
     * @see Tree#remove(String)
     */
    public static void remove(String matching) {
        tree().remove(matching);
    }

    public static void reload(Map<String, ?> words) {
        tree().reload(words);
    }

    /**
     * 默认 scene 当前有效词条数量.
     * @see Tree#size()
     */
    public static int size() {
        return tree().size();
    }

    /**
     * 默认 scene 当前是否没有有效词条.
     * @see Tree#isEmpty()
     */
    public static boolean isEmpty() {
        return tree().isEmpty();
    }

    public static String filter(String text) {
        return tree().filter(text);
    }

    public static void filter(String text, Consumer<Character> consumer) {
        tree().filter(text, consumer);
    }

    public static <T> void matching(String text, Consumer3<String, String, T> consumer) {
        tree().matching(text, consumer);
    }

    public static <T> T matchingGet(String text, Supplier<T> dft) {
        return tree().matchingGet(text, dft);
    }

    public static <T> T matchingGet(String text, T dft) {
        return tree().matchingGet(text, dft);
    }

    /**
     * 默认 scene 中扫描整段 text, 判断是否包含任意词条.
     * @see Tree#contains(String)
     */
    public static boolean contains(String text) {
        return tree().contains(text);
    }

    /**
     * 默认 scene 中判断 text 是否被某个词条完整匹配.
     * @see Tree#containsExact(String)
     */
    public static boolean containsExact(String text) {
        return tree().containsExact(text);
    }

    /**
     * 默认 scene 中精确匹配 text 并返回替换值.
     * @see Tree#exactGet(String, Supplier)
     */
    public static <T> T exactGet(String text, Supplier<T> dft) {
        return tree().exactGet(text, dft);
    }

    /**
     * 默认 scene 中精确匹配 text 并返回替换值.
     * @see Tree#exactGet(String, Object)
     */
    public static <T> T exactGet(String text, T dft) {
        return tree().exactGet(text, dft);
    }

    /**
     * 默认 scene 中从 text 开头做短词优先的前缀匹配.
     * @see Tree#prefixGet(String, Supplier)
     */
    public static <T> T prefixGet(String text, Supplier<T> dft) {
        return tree().prefixGet(text, dft);
    }

    /**
     * 默认 scene 中从 text 开头做短词优先的前缀匹配.
     * @see Tree#prefixGet(String, Object)
     */
    public static <T> T prefixGet(String text, T dft) {
        return tree().prefixGet(text, dft);
    }

    /**
     * 默认 scene 中从 text 开头做最长前缀匹配.
     * @see Tree#longestPrefixGet(String, Supplier)
     */
    public static <T> T longestPrefixGet(String text, Supplier<T> dft) {
        return tree().longestPrefixGet(text, dft);
    }

    /**
     * 默认 scene 中从 text 开头做最长前缀匹配.
     * @see Tree#longestPrefixGet(String, Object)
     */
    public static <T> T longestPrefixGet(String text, T dft) {
        return tree().longestPrefixGet(text, dft);
    }

    /**
     * 默认 scene 中判断词库里是否存在以 prefix 开头的词条.
     * @see Tree#hasPrefix(String)
     */
    public static boolean hasPrefix(String prefix) {
        return tree().hasPrefix(prefix);
    }

    /**
     * 默认 scene 中列出所有以 prefix 开头的词条.
     * @see Tree#wordsWithPrefix(String)
     */
    public static List<String> wordsWithPrefix(String prefix) {
        return tree().wordsWithPrefix(prefix);
    }

    /**
     * 默认 scene 中列出最多 limit 个以 prefix 开头的词条.
     * @see Tree#wordsWithPrefix(String, int)
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

        Tree(String scene) {
            this.scene = scene;
        }

        /**
         * 设置配置 (下次 load/reload/clear 重建快照时生效)
         * @param ignoreCase 是否忽略大小写
         * @param ignoreSymbols 是否忽略特殊符号
         * @param lastStar 是否支持匹配词最后一位*号匹配
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
         * 清除匹配词
         */
        public void clear() {
            SyncLock.lock("Trie.Tree", 1, "Trie.Tree", () -> {
                this.entries.clear();
                this.publish();
                log.info("Trie's Tree[{}] clear matching words", this.scene);
            });
        }

        /**
         * 加载单个匹配词 (增量, 每次按全量词条重建发布; 适合启动期或低频配置).
         * 高频/批量场景请用 {@link #reload(Map)} 一次性发布, 避免 O(n^2) 重建.
         * @param matching 匹配词
         * @param replaced 替换词
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
         * 移除一个已加载匹配词.
         * <p>
         * 这是词库维护方法, 不做文本扫描. {@code matching} 必须与加载时的原始 key 完全一致;
         * 当前配置下的大小写归一化只影响查询和构建, 不影响 entries 中 key 的删除。
         * <pre>{@code
         * tree.load("app", "APP");
         * tree.remove("app");
         * tree.containsExact("app"); // false
         * }</pre>
         *
         * @param matching 要移除的匹配词; null/空串会被忽略
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
         * 批量整体替换匹配词, 一次性离线构建新树后原子发布.
         * 读线程要么看到旧全量树、要么看到新全量树, 不会撞见半加载状态.
         * @param words 匹配词 -> 替换词
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

        /* 按当前 entries + 配置离线构建一棵新树, 原子发布到 volatile state. 仅在写锁内调用. */
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
         * 返回当前快照中的有效词条数量 (= 加载条目数).
         * <p>
         * 按加载时的原始 key 计数, null/空 key 在 load/reload 时已被跳过、不计入;
         * <b>不做归一化去重</b>: {@code ignoreCase=true} 下分别加载 {@code "Bad"} 与 {@code "bad"}
         * 会算作 2 (尽管它们命中同一节点)。
         * <pre>{@code
         * tree.reload(Map.of("app", "APP", "bad", "BAD"));
         * tree.size(); // 2
         * }</pre>
         *
         * @return 当前已发布快照中的有效词条数 (加载条目数)
         */
        public int size() {
            return this.state.size;
        }

        /**
         * 判断当前快照是否没有有效词条.
         * <pre>{@code
         * tree.clear();
         * tree.isEmpty(); // true
         * }</pre>
         *
         * @return true 表示当前没有任何有效匹配词
         */
        public boolean isEmpty() {
            return this.state.size == 0;
        }

        /**
         * 操作字符串，过滤替换匹配词，返回过滤后文本
         */
        public String filter(String text) {
            Objects.requireNonNull(text, "text");
            StringBuilder sb = new StringBuilder(text.length());
            this.filter(text, sb::append);
            return sb.toString();
        }

        /**
         * 操作字符串，过滤替换匹配词，消费每一个字符
         */
        public void filter(String text, Consumer<Character> consumer) {
            Objects.requireNonNull(text, "text");
            this.state.filter(text, consumer);
        }

        /**
         * 检测匹配词并消费 Consumer3(text, matching, replaced)
         */
        public <T> void matching(String text, Consumer3<String, String, T> consumer) {
            Objects.requireNonNull(text, "text");
            this.state.matching(text, consumer);
        }

        /**
         * 匹配并获取替换词，没有则返回dft
         */
        public <T> T matchingGet(String text, Supplier<T> dft) {
            Objects.requireNonNull(text, "text");
            Object o = this.state.matchingGet(text, DEFAULT);
            return o == DEFAULT ? dft.get() : (T) o;
        }

        /**
         * 匹配并获取替换词，没有则返回dft
         */
        public <T> T matchingGet(String text, T dft) {
            Objects.requireNonNull(text, "text");
            return this.state.matchingGet(text, dft);
        }

        /**
         * 扫描整段 text, 判断是否<b>包含</b>任意匹配词 (子串包含).
         * <p>
         * <b>匹配方式: 子串扫描。</b>从 text 的<b>每一个</b>下标 {@code i} 作为起点尝试沿 trie 下行,
         * 任意一个起点命中词条即返回 true; 与 {@link #matchingGet(String, Object)} / {@link #filter(String)}
         * 同源, 是"text 里有没有出现敏感词"的判断。基础语义为短词优先 (遇第一个 end 即算命中);
         * {@code lastStar=true} 时仅词条末位 {@code *} 作通配, 且至少吃 1 个剩余字符。
         * <p>
         * <b>匹配过程示例</b> (词 {@code "bad"}, text {@code "xxbadyy"}):
         * <pre>
         *   i=0 'x' → root 无 'x' 子节点, 断链
         *   i=1 'x' → 断链
         *   i=2 'b' → 'a' → 'd' 命中 end ⇒ 返回 true
         * </pre>
         * <pre>{@code
         * tree.load("bad", "BAD");
         * tree.contains("xxbadyy"); // true  (中间含 bad)
         * tree.contains("clean");   // false (任何起点都走不到 end)
         *
         * tree.conf(false, false, true);
         * tree.load("/api/*", "API");
         * tree.contains("x/api/a"); // true  (从下标 1 起 /api/ + 通配吃 'a')
         * tree.contains("/api/");   // false (* 不匹配空后缀)
         * }</pre>
         *
         * @param text 要扫描的文本; 不能为 null
         * @return true 表示 text 中至少出现一个匹配词
         */
        public boolean contains(String text) {
            Objects.requireNonNull(text, "text");
            return this.state.contains(text);
        }

        /**
         * 判断整段 text 是否被某个词条<b>完整</b>匹配 (整串等值).
         * <p>
         * <b>匹配方式: 整段完整匹配 (两端锚定)。</b>只从下标 0 起下行, 且必须<b>恰好吃到 text 末尾、
         * 落在 end 节点</b>才算命中——既不在中间起、也不允许有多余后缀。与 {@link #contains(String)}
         * 的"子串包含"相反。{@code lastStar=true} 时末位 {@code *} 可吃掉剩余字符 (≥1 个) 当作完整匹配。
         * <p>
         * <b>匹配过程示例</b> (词 {@code "app"}):
         * <pre>
         *   "app"   → a→p→p 到末尾且 end ⇒ true
         *   "apple" → a→p→p 命中 end 但还剩 "le" 未吃完 ⇒ false (有多余后缀)
         *   "xapp"  → 下标 0 是 'x', root 无 'x' ⇒ false (未从开头匹配)
         * </pre>
         * <pre>{@code
         * tree.load("app", "APP");
         * tree.containsExact("app");    // true
         * tree.containsExact("xapp");   // false (不从开头)
         * tree.containsExact("apple");  // false (有多余后缀)
         *
         * tree.conf(false, false, true);
         * tree.load("/api/*", "API");
         * tree.containsExact("/api/a"); // true  (末位 * 吃掉 'a')
         * tree.containsExact("/api/");  // false (* 至少吃 1 个字符)
         * }</pre>
         *
         * @param text 要完整匹配的文本; 不能为 null
         * @return true 表示整段 text 被某个词条或末尾通配模式完整匹配
         */
        public boolean containsExact(String text) {
            Objects.requireNonNull(text, "text");
            return this.state.exactGet(text, DEFAULT) != DEFAULT;
        }

        /**
         * 精确匹配整段 text, 命中返回该词条的替换值, 未命中调用 {@code dft} 取默认值.
         * <p>
         * <b>匹配方式: 整段完整匹配 (两端锚定)</b>, 同 {@link #containsExact(String)}——
         * 从下标 0 起、必须吃到 text 末尾且落在 end 节点。是 {@link #containsExact(String)} 的"取值版"。
         * {@code dft} 仅在未命中时才求值。
         * <p>
         * <b>匹配过程示例</b> (词 {@code "apple"}):
         * <pre>
         *   "apple"  → a→p→p→l→e 到末尾且 end ⇒ 返回 "APPLE"
         *   "apple2" → 走完 "apple" 命中 end 但还剩 '2' ⇒ 未完整匹配 ⇒ dft
         * </pre>
         * <pre>{@code
         * tree.load("apple", "APPLE");
         * tree.exactGet("apple", () -> "DFT");  // "APPLE"
         * tree.exactGet("apple2", () -> "DFT"); // "DFT"  (多余后缀, 不算完整)
         * }</pre>
         *
         * @param text 要完整匹配的文本; 不能为 null
         * @param dft 未命中时的默认值供应器 (仅未命中时调用)
         * @param <T> 替换值类型
         * @return 命中的替换值; 未命中返回 dft.get()
         */
        public <T> T exactGet(String text, Supplier<T> dft) {
            Objects.requireNonNull(text, "text");
            Object o = this.state.exactGet(text, DEFAULT);
            return o == DEFAULT ? dft.get() : (T) o;
        }

        /**
         * 精确匹配整段 text, 命中返回该词条的替换值, 未命中返回 {@code dft}.
         * <p>
         * <b>匹配方式: 整段完整匹配 (两端锚定)</b>, 同 {@link #containsExact(String)}——
         * 从下标 0 起、必须吃到 text 末尾且落在 end 节点。常量默认值版本, 适合配置查表。
         * <pre>{@code
         * tree.load("apple", "APPLE");
         * tree.exactGet("apple", "DFT");  // "APPLE"
         * tree.exactGet("apple2", "DFT"); // "DFT"  (多余后缀, 不算完整)
         * }</pre>
         *
         * @param text 要完整匹配的文本; 不能为 null
         * @param dft 未命中时返回的默认值
         * @param <T> 替换值类型
         * @return 命中的替换值; 未命中返回 dft
         */
        public <T> T exactGet(String text, T dft) {
            Objects.requireNonNull(text, "text");
            return this.state.exactGet(text, dft);
        }

        /**
         * 从 text <b>开头</b>做前缀匹配, 命中第一个 (最短) 词条就返回替换值, 未命中调用 {@code dft}.
         * <p>
         * <b>匹配方式: 开头锚定 + 短词优先。</b>只从下标 0 起下行 (不扫描中间位置, 区别于 {@link #contains});
         * 不要求吃到 text 末尾 (区别于 {@link #exactGet}); 遇到<b>第一个</b> end 节点就返回, 不再向后找更长词
         * (区别于 {@link #longestPrefixGet})。
         * <p>
         * <b>匹配过程示例</b> (词 {@code app}/{@code apple}, text {@code "applepie"}):
         * <pre>
         *   下标0起: a→p→p 命中 end "app" ⇒ 立即返回 APP (不再继续看 apple)
         * </pre>
         * <pre>{@code
         * tree.load("app", "APP");
         * tree.load("apple", "APPLE");
         * tree.prefixGet("applepie", () -> "DFT"); // "APP"  (短词优先, 命中即返回)
         * tree.prefixGet("xapple", () -> "DFT");   // "DFT"  (开头是 x, 不从中间扫)
         * }</pre>
         *
         * @param text 要从开头匹配的文本; 不能为 null
         * @param dft 未命中时的默认值供应器 (仅未命中时调用)
         * @param <T> 替换值类型
         * @return 第一个 (最短) 前缀词条的替换值; 未命中返回 dft.get()
         */
        public <T> T prefixGet(String text, Supplier<T> dft) {
            Objects.requireNonNull(text, "text");
            Object o = this.state.prefixGet(text, DEFAULT);
            return o == DEFAULT ? dft.get() : (T) o;
        }

        /**
         * 从 text <b>开头</b>做前缀匹配, 命中第一个 (最短) 词条就返回替换值, 未命中返回 {@code dft}.
         * <p>
         * <b>匹配方式: 开头锚定 + 短词优先</b> (遇第一个 end 即返回)。常量默认值版本。
         * 需要"更具体/更长词条优先"时改用 {@link #longestPrefixGet(String, Object)}。
         * <pre>{@code
         * tree.load("app", "APP");
         * tree.load("apple", "APPLE");
         * tree.prefixGet("applepie", "DFT"); // "APP"  (短词优先)
         * }</pre>
         *
         * @param text 要从开头匹配的文本; 不能为 null
         * @param dft 未命中时返回的默认值
         * @param <T> 替换值类型
         * @return 第一个 (最短) 前缀词条的替换值; 未命中返回 dft
         */
        public <T> T prefixGet(String text, T dft) {
            Objects.requireNonNull(text, "text");
            return this.state.prefixGet(text, dft);
        }

        /**
         * 从 text <b>开头</b>做<b>最长</b>前缀匹配, 命中返回最长词条的替换值, 未命中调用 {@code dft}.
         * <p>
         * <b>匹配方式: 开头锚定 + 最长优先。</b>从下标 0 起一路下行, 沿途记录"最近一次经过的 end 节点",
         * 直到断链或走到末尾, 取记录到的<b>最长</b>那个精确词 (区别于 {@link #prefixGet} 的遇第一个就停)。
         * 适合 URL 路由、配置覆盖、自动补全等"更具体配置优先"场景。
         * <p>
         * <b>lastStar 下的优先级</b>: 完整精确词 (吃到末尾) &gt; 末位 {@code *} 通配 (吃到末尾) &gt; 较短的精确词。
         * <p>
         * <b>匹配过程示例</b> (词 {@code app}/{@code apple}, text {@code "applepie"}):
         * <pre>
         *   a→p→p 记下 end "app" → l→e 记下更长 end "apple" → p 断链 ⇒ 取最长 "apple" = APPLE
         * </pre>
         * <pre>{@code
         * tree.load("app", "APP");
         * tree.load("apple", "APPLE");
         * tree.longestPrefixGet("applepie", () -> "DFT"); // "APPLE" (最长精确)
         *
         * tree.conf(false, false, true);
         * tree.load("/api", "SHORT");
         * tree.load("/api/*", "WILD");
         * tree.load("/api/healx", "HEALX");
         * // /api/other: 记下精确 "/api"=SHORT, 再走 /api/ 后 'o' 断链, 但 * 能吃到末尾且比 /api 长 ⇒ WILD
         * tree.longestPrefixGet("/api/other", () -> "DFT"); // "WILD"
         * // /api/healx: 一路精确到末尾命中 "/api/healx", 完整精确优先于通配 ⇒ HEALX
         * tree.longestPrefixGet("/api/healx", () -> "DFT"); // "HEALX"
         * }</pre>
         *
         * @param text 要从开头匹配的文本; 不能为 null
         * @param dft 未命中时的默认值供应器 (仅未命中时调用)
         * @param <T> 替换值类型
         * @return 最长前缀词条的替换值; 未命中返回 dft.get()
         */
        public <T> T longestPrefixGet(String text, Supplier<T> dft) {
            Objects.requireNonNull(text, "text");
            Object o = this.state.longestPrefixGet(text, DEFAULT);
            return o == DEFAULT ? dft.get() : (T) o;
        }

        /**
         * 从 text <b>开头</b>做<b>最长</b>前缀匹配, 命中返回最长词条的替换值, 未命中返回 {@code dft}.
         * <p>
         * <b>匹配方式: 开头锚定 + 最长优先</b>, 规则同 {@link #longestPrefixGet(String, Supplier)}。常量默认值版本。
         * <pre>{@code
         * tree.load("app", "APP");
         * tree.load("apple", "APPLE");
         * tree.longestPrefixGet("applepie", "DFT"); // "APPLE" (最长精确, 区别于 prefixGet 的 APP)
         * }</pre>
         *
         * @param text 要从开头匹配的文本; 不能为 null
         * @param dft 未命中时返回的默认值
         * @param <T> 替换值类型
         * @return 最长前缀词条的替换值; 未命中返回 dft
         */
        public <T> T longestPrefixGet(String text, T dft) {
            Objects.requireNonNull(text, "text");
            return this.state.longestPrefixGet(text, dft);
        }

        /**
         * 判断词库中是否存在以 {@code prefix} 开头的词条.
         * <p>
         * <b>匹配方式: 词库结构查询 (不是文本匹配)。</b>沿 {@code prefix} 的字符在 trie 里逐级下行,
         * 只看"这条前缀路径在不在", <b>不要求落在 end 节点</b>、也不扫描任何正文。
         * 与 exact/prefix 系列的区别: 那些是拿 text 去比词条, 这个是问"词库里有没有这个前缀分支"。
         * 对 prefix 仍按当前 {@code ignoreCase} 归一化, 但<b>不</b>跳符号 (按存储的 key 字符比对)。
         * <p>
         * <b>匹配过程示例</b> (词库 {@code app}/{@code apple}):
         * <pre>
         *   "ap" → a→p 路径存在 (虽非 end) ⇒ true
         *   "ax" → a→x 无该子节点 ⇒ false
         * </pre>
         * <pre>{@code
         * tree.load("app", "APP");
         * tree.load("apple", "APPLE");
         * tree.hasPrefix("ap");  // true  (app/apple 都挂在 ap 下)
         * tree.hasPrefix("app"); // true  (app 本身 + apple)
         * tree.hasPrefix("ax");  // false
         * }</pre>
         *
         * @param prefix 词条前缀; null 返回 false (结构查询对 null 宽松, 不抛异常)
         * @return true 表示至少存在一个词条以 prefix 开头
         */
        public boolean hasPrefix(String prefix) {
            return this.state.hasPrefix(prefix);
        }

        /**
         * 列出词库中以 {@code prefix} 开头的<b>全部</b>词条.
         * <p>
         * <b>匹配方式: 词库枚举 (不是文本匹配)。</b>先用 {@link #hasPrefix} 同样的方式定位到 prefix 路径末端节点,
         * 再从该节点 DFS 向下收集所有 end 节点上保存的词条 word。常用于自动补全、配置检查、调试词库。
         * <ul>
         *   <li>返回的是构建时存的词条原文 word; {@code ignoreCase=true} 且加载过大小写变体时,
         *       word 可能保留<b>第一次</b>创建该路径时的大小写。</li>
         *   <li>结果<b>顺序不保证</b> (取决于内部哈希槽顺序), 需要稳定顺序请自行排序。</li>
         * </ul>
         * <p>
         * <b>匹配过程示例</b> (词库 {@code app}/{@code apple}/{@code bad}, prefix {@code "app"}):
         * <pre>
         *   定位到 app 末端节点 → DFS: 该节点是 end 收 "app" → 子节点 l→e 是 end 收 "apple"
         *   ⇒ ["app", "apple"] (不含 bad, 因为不在 app 分支下)
         * </pre>
         * <pre>{@code
         * tree.load("app", "APP");
         * tree.load("apple", "APPLE");
         * tree.load("bad", "BAD");
         * tree.wordsWithPrefix("app"); // ["app", "apple"] (顺序不保证)
         * }</pre>
         *
         * @param prefix 词条前缀; null 返回空列表 (结构查询对 null 宽松)
         * @return 以 prefix 开头的词条列表; 未命中返回空列表
         */
        public List<String> wordsWithPrefix(String prefix) {
            return this.state.wordsWithPrefix(prefix, Integer.MAX_VALUE);
        }

        /**
         * 列出词库中以 {@code prefix} 开头的词条, <b>最多 {@code limit} 个</b>.
         * <p>
         * <b>匹配方式: 词库枚举</b>, 同 {@link #wordsWithPrefix(String)}, 但 DFS 收集到 {@code limit} 个即停止,
         * 适合自动补全时限制候选数量。同样顺序不保证。
         * <pre>{@code
         * tree.load("app", "APP");
         * tree.load("apple", "APPLE");
         * tree.wordsWithPrefix("app", 1); // 1 个 (app 或 apple, 取决于遍历顺序)
         * }</pre>
         *
         * @param prefix 词条前缀; null 返回空列表
         * @param limit 最大返回数量; ≤0 时返回空列表
         * @return 以 prefix 开头的词条列表 (≤ limit); 未命中返回空列表
         */
        public List<String> wordsWithPrefix(String prefix, int limit) {
            return this.state.wordsWithPrefix(prefix, limit);
        }
    }

    /* 构建期/查询期共用的大小写归一化: 大写转小写 */
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

        State(Node root, boolean ignoreCase, boolean ignoreSymbols, boolean lastStar, int size) {
            this.root = root;
            this.ignoreCase = ignoreCase;
            this.ignoreSymbols = ignoreSymbols;
            this.lastStar = lastStar;
            this.size = size;
        }

        private boolean ignoreSymbols(char c) {
            // 忽略特殊符号
            return this.ignoreSymbols && ((c >= 32 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96) || (c >= 123 && c <= 126));
        }

        /**
         * 从 text[from] 起做单次最长匹配.
         * <p>
         * 精确子节点优先逐字符下降; lastStar 下沿途记录"最近祖先的终止 '*' 通配候选".
         * 命中精确 end 节点立即返回该节点 (精确优先); 精确路径断链或走到文本末仍未命中精确词,
         * 则回退到最近的 '*' 候选. 都没有则返回 null.
         *
         * @param endExclusive 出参 (长度≥1): 命中时写入消费到的下一个起点下标 (精确=匹配末尾+1, 通配=文本末尾)
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

        /*
         * 从 text[from] 起找最长前缀. 精确匹配到文本末尾优先于通配; 否则末尾 * 通配按吃完整段文本处理.
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

        /*
         * 整段 text 必须被词条或末尾 * 通配完整消费. 不使用 match(), 避免短词优先导致 abc 被 ab 截断.
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

        private Node prefixNode(String prefix) {
            if (prefix == null) return null;
            Node node = this.root;
            for (int i = 0; i < prefix.length(); i++) {
                node = node.children.get(Trie.normalizeCase(prefix.charAt(i), this.ignoreCase));
                if (node == null) return null;
            }
            return node;
        }

        private void collectWords(Node node, List<String> result, int limit) {
            if (result.size() >= limit) return;
            if (node.end) result.add(node.word);
            if (result.size() >= limit) return;
            node.children.forEach(child -> this.collectWords(child, result, limit));
        }

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

        boolean contains(String text) {
            if (this.root.children.isEmpty()) return false;
            int[] end = new int[1];
            for (int i = 0; i < text.length(); i++) {
                if (this.match(text, i, end) != null) return true;
            }
            return false;
        }

        <T> T matchingGet(String text, T dft) {
            if (this.root.children.isEmpty()) return dft;
            int[] end = new int[1];
            for (int i = 0; i < text.length(); i++) {
                Node hit = this.match(text, i, end);
                if (hit != null) return (T) hit.replaced;
            }
            return dft;
        }

        <T> T exactGet(String text, T dft) {
            if (this.root.children.isEmpty()) return dft;
            Node hit = this.exactMatch(text);
            return hit == null ? dft : (T) hit.replaced;
        }

        <T> T prefixGet(String text, T dft) {
            if (this.root.children.isEmpty()) return dft;
            int[] end = new int[1];
            Node hit = this.match(text, 0, end);
            return hit == null ? dft : (T) hit.replaced;
        }

        <T> T longestPrefixGet(String text, T dft) {
            if (this.root.children.isEmpty()) return dft;
            int[] end = new int[1];
            Node hit = this.longestMatch(text, 0, end);
            return hit == null ? dft : (T) hit.replaced;
        }

        boolean hasPrefix(String prefix) {
            if (this.root.children.isEmpty()) return false;
            Node node = this.prefixNode(prefix);
            return node != null && (node != this.root || !node.children.isEmpty());
        }

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

        boolean isEmpty() {
            return this.size == 0;
        }

        void forEach(Consumer<Node> consumer) {
            Node[] vals = this.vals;
            if (vals == null) return;
            for (Node v : vals) {
                if (v != null) consumer.accept(v);
            }
        }
    }
}
