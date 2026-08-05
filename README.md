# nasa-core

`nasa-core` 是从 Nasa Runtime 中拆分出的独立 Maven 基础库，提供基础类型、并发容器、对象池、协议编解码、反射、集合、日期时间和 JSON 等通用能力。

项目坐标：`com.nasa.runtime:nasa-core:1.0.0`

## 环境要求

- JDK 21 或更高版本
- Maven 3.6.3 或更高版本

## 构建

```bash
mvn -B -ntp clean verify
```

## 引用

```xml
<dependency>
    <groupId>com.nasa.runtime</groupId>
    <artifactId>nasa-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

该模块是独立的纯 Java 项目，不继承外部 parent POM，也不要求应用容器。日志仅依赖 `slf4j-api`，由使用方选择具体日志实现。

## 主要能力

- 环形集合、并发容器、对象池和线程池工具。
- 基于 `Object[]` 或 `byte[]` 的协议编解码能力。
- 进程内实例注册、类型转换、反射和 JavaBeans 工具。
- Jackson、Fastjson2、日期时间、字符串、数值和集合辅助能力。
- 面向 GraalVM native-image 的可选类型注册入口。

## 独立化后的边界

- `ContextUtils` 使用进程内实例注册表；可通过 `registerSingleton` 显式装配协议转换器等可选组件。
- `ThreadPoolInitializer.initialize(object)` 显式初始化 `@ThreadPool` 标注的字段和 setter。
- `VirtualThreadProperties.apply()` 在首个虚拟线程创建前发布 JDK 调度器参数。
- 容器生命周期、事务管理器适配以及自动配置元数据由应用集成层负责，不进入核心库。

## 协议兼容性

- `Protocol` 和 `ProtocolBytes` 只处理标有 `@Protocols` 的字段；没有标注字段时编码结果为空。
- `@Protocols.value()` 是稳定线路标识。已发布后不要复用或修改现有字段的值。
- `Protocol.Mode.DENSE` 是默认的历史格式；切换模式会改变线路结构，收发两端必须同步。
- `ProtocolBytes.Mode.VARINT_TLV` 使用 protobuf 风格的 wire type 和 tag 编码，但集合等扩展布局属于本项目协议，不能直接替代由 `.proto` 生成的消息类型。
- `ProtocolBytes` 的二进制模式按枚举 `ordinal` 编码；枚举常量一经发布只能在末尾追加，不能插入、删除或重排。
- `BITMAP`、`BITPACK_TLV` 和 `FAST_FIXED` 依赖稳定字段布局，修改 schema 前必须评估历史消息和旧消费者。

## 原生镜像

`NasaNativeFeature` 可通过系统属性 `nasa.native.serialization.package` 指定需要自动注册序列化类型的基础包：

```text
-Dnasa.native.serialization.package=com.example.app
```

构建 native-image 时还需显式启用 Feature：

```text
--features=com.nasa.runtime.core.feature.NasaNativeFeature
```

未配置扫描包时只注册 `nasa-core` 内建的基础类型。

## JDK 版本基线

当前最低要求固定为 JDK 21。计时轮和线程池相关能力使用虚拟线程，同时集合与类型转换代码也采用 Java 21 API 和语法。

编译产物使用 Java 21 字节码，低于 JDK 21 的项目无法加载本库。

## 文档与发布

- 贡献方式见 [CONTRIBUTING.md](CONTRIBUTING.md)。
- 安全问题报告方式见 [SECURITY.md](SECURITY.md)。
- Central Portal 和 GitHub 的发布流程见 [RELEASING.md](RELEASING.md)。
- 版本变化见 [CHANGELOG.md](CHANGELOG.md)。

## 许可证

本项目采用 `Apache-2.0 OR MIT` 双许可证，使用方可任选其一。详见 [LICENSE-APACHE](LICENSE-APACHE) 和 [LICENSE-MIT](LICENSE-MIT)。
