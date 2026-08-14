# 发布指南

本文说明如何把 nasa-core 发布到 GitHub 和 Central Portal。Central 中的已发布版本不可覆盖或删除，因此每次发布前必须完整核对坐标和产物。

## 1. 发布前条件

- Central Portal 当前发布账户中存在状态为 `Verified`、且覆盖 `io.github.nasa-runtime` 的 namespace。
  仓库文档不能替代 Portal 的实际授权状态，发布前必须在 Portal 页面确认。
- Central Portal 已生成 User Token。
- 本机已配置 GPG 主签名密钥，公钥已上传至 Central 支持的公开 key server。
- Git 工作区干净，`pom.xml`、README 和变更记录中的版本一致。
- `CHANGELOG.md` 中本次版本已经从“未发布”切换为实际发布日期。
- 当前提交已推送到 `https://github.com/nasa-runtime/nasa-core`。
- 公开提交和发布归档不含 `src/test`、`test` 或根级 `tests/`；根级 `tests/` 即使在本机存在，也必须
  保持被 `.gitignore` 排除且 `git ls-files tests` 结果为空。

Central 凭证放在用户级 `~/.m2/settings.xml`，不要写入项目：

```xml
<settings>
    <servers>
        <server>
            <id>central</id>
            <username>${env.CENTRAL_USERNAME}</username>
            <password>${env.CENTRAL_PASSWORD}</password>
        </server>
    </servers>
</settings>
```

GPG 口令应由 `gpg-agent` 或受保护的环境变量提供，不要放入命令历史、POM 或仓库文件。

若当前账户没有覆盖 `io.github.nasa-runtime` 的已验证 namespace，禁止直接上传。

本项目刻意选用 `io.github.<组织名>` 而非域名型 namespace：域名型（如 `com.nasa.runtime`）要求按
Central Portal 的 DNS TXT 流程证明对根域名 `nasa.com` 的控制权，该域名并不属于本项目。

`io.github.nasa-runtime` 的验证方式：在 Central Portal 添加该 namespace，Portal 会给出一个随机
仓库名，在 GitHub 组织 `nasa-runtime` 下创建同名公开仓库即可完成验证。注意 GitHub 登录只会
自动授予 `io.github.<登录用户名>`，组织 namespace 不会因为组织存在而自动授予，必须走上述显式验证。

任何 groupId 迁移都必须在首次发布前完成，并同步修改 `pom.xml`、README 和本指南；坐标一旦发布
不可覆盖，也无法把旧版本迁移到新坐标。

## 2. 核对本地产物

```bash
mvn -B -ntp clean verify
```

基础构建中的 Maven Enforcer 会拒绝产品源码树出现 `src/test` 或 `test`。根级 `tests/` 属仅本地使用的
质量工程，不参与 Maven 构建、公开提交或产品归档。

`target/` 中必须至少存在：

- `nasa-core-1.0.2.jar`
- `nasa-core-1.0.2-sources.jar`
- `nasa-core-1.0.2-javadoc.jar`

同时核对主 JAR 不含本机元数据，POM 不含快照依赖，编译字节码版本为 Java 21。

## 3. 上传 Central Portal

```bash
mvn -B -ntp -Pcentral-release clean deploy
```

`central-release` profile 会拒绝 SNAPSHOT 项目版本和 SNAPSHOT 依赖，为 POM、主 JAR、sources JAR 和 Javadoc JAR 生成 GPG 签名，并由 Central Publishing Maven Plugin 生成校验和、上传 deployment。配置默认不自动发布；上传验证通过后先不要点击 Publish，继续完成下一节的不可变源码标签核对。

## 4. 固化源码并发布

Central deployment 验证通过后，在当前构建提交上创建并推送与 POM `<scm><tag>` 一致的签名标签：

```bash
git tag -s v1.0.2 -m "nasa-core 1.0.2"
git push origin v1.0.2
```

确认远端标签准确指向本次构建提交且仓库仍然干净，然后回到 Central Portal 点击 Publish；Portal 页面不可用时，也可以使用同一 User Token 调用官方 `POST /api/v1/publisher/deployment/<deploymentId>` 接口。Central 发布成功后在 GitHub 创建 `v1.0.2` Release，发布说明以 `CHANGELOG.md` 对应版本为准。不要把本机签名私钥、Central Token 或用户级 Maven 配置作为附件上传。
