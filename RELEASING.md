# 发布指南

本文说明如何把 nasa-core 发布到 GitHub 和 Central Portal。Central 中的已发布版本不可覆盖或删除，因此每次发布前必须完整核对坐标和产物。

## 1. 发布前条件

- `com.nasa.runtime` 已在 Central Portal 中完成 namespace 验证。
- Central Portal 已生成 User Token。
- 本机已配置 GPG 主签名密钥，公钥已上传至 Central 支持的公开 key server。
- Git 工作区干净，`pom.xml`、README 和变更记录中的版本一致。
- 当前提交已推送到 `https://github.com/nasa-runtime/nasa-core`。

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

## 2. 核对本地产物

```bash
mvn -B -ntp clean verify
```

`target/` 中必须至少存在：

- `nasa-core-1.0.0.jar`
- `nasa-core-1.0.0-sources.jar`
- `nasa-core-1.0.0-javadoc.jar`

同时核对主 JAR 不含本机元数据，POM 不含快照依赖，编译字节码版本为 Java 21。

## 3. 上传 Central Portal

```bash
mvn -B -ntp -Pcentral-release clean deploy
```

`central-release` profile 会为 POM、主 JAR、sources JAR 和 Javadoc JAR 生成 GPG 签名，并由 Central Publishing Maven Plugin 生成校验和、上传 deployment。配置默认不自动发布；上传验证通过后，必须在 Central Portal 再次核对坐标和文件，再手动点击 Publish。

## 4. 发布 GitHub Release

Central 发布成功后创建并推送对应标签：

```bash
git tag -s v1.0.0 -m "nasa-core 1.0.0"
git push origin v1.0.0
```

在 GitHub 创建 `v1.0.0` Release，发布说明以 `CHANGELOG.md` 对应版本为准。不要把本机签名私钥、Central Token 或用户级 Maven 配置作为附件上传。
