# DataSmart Govern Backend JDK 21 构建说明

本文档用于固定本项目的 Java/Maven 构建约定，避免本机默认 Maven 使用 Java 8、但项目源码使用 Java 21 语法时出现“假失败”。

## 为什么必须使用 JDK 21

项目根 `pom.xml` 已统一声明：

- `java.version=21`
- `maven-compiler-plugin` 使用 `<release>21</release>`
- `maven-toolchains-plugin` 在 `validate` 阶段选择 `[21,22)` 范围内的 JDK

这意味着项目源码、测试、Spring Boot 3.5.x 生态和后续虚拟线程等能力都按 JDK 21 设计。即使本机 `mvn -v` 显示 Java 8，只要机器上安装了 JDK 21 且 Maven Toolchains 能发现它，编译和测试也应由 JDK 21 执行。

## 推荐验证命令

先查看 Maven 当前启动 JDK：

```powershell
mvn -v
```

再查看 Maven Toolchains Plugin 能发现哪些 JDK：

```powershell
mvn org.apache.maven.plugins:maven-toolchains-plugin:3.2.0:display-discovered-jdk-toolchains
```

如果输出里能看到 `21.x`，可以直接运行：

```powershell
mvn test -DskipITs "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"
```

构建日志中应出现类似 `Found matching toolchain` 或 `Toolchain in maven-compiler-plugin` 的信息，表示编译/测试已经切换到 JDK 21。

## 自动发现失败时的兜底方案

如果 Maven 没有自动发现 JDK 21，可以配置用户级 `~/.m2/toolchains.xml`。注意不要把真实个人路径提交到仓库，因为团队成员、CI 机器和服务器路径都不同。

Windows 示例：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>21</version>
    </provides>
    <configuration>
      <jdkHome>C:\Users\Cui\.jdks\temurin-21.0.10</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
```

Linux/macOS 示例：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>21</version>
    </provides>
    <configuration>
      <jdkHome>/opt/jdk/temurin-21</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
```

也可以设置环境变量，例如 `JAVA21_HOME` 指向 JDK 21 安装目录，再重新执行发现命令。

## 常见误区

- 只设置 `maven-compiler-plugin <release>21</release>` 不等于 Maven 一定会用 JDK 21。如果 Maven 本身跑在 Java 8 上，且没有 toolchain，仍可能在解析 Java 21 语法时失败。
- 不建议在 `pom.xml` 中写死 `C:\Users\...` 这样的本机 JDK 路径。项目级 POM 应表达“需要 JDK 21”，具体路径应由本机 toolchains、环境变量或 CI 配置提供。
- 如果 IDE 内置 Maven 使用自己的 JDK，需要在 IDE 的 Maven Runner/JDK 设置里也选择 JDK 21，或者确认 IDE Maven 能读取用户级 `toolchains.xml`。

## 本项目当前推荐做法

1. 本机安装 Eclipse Temurin JDK 21 或其他 JDK 21 发行版。
2. 优先让 `maven-toolchains-plugin:3.2.0` 自动发现 JDK 21。
3. 自动发现失败时，再配置用户级 `~/.m2/toolchains.xml`。
4. CI/CD 中显式安装 JDK 21，并保留根 `pom.xml` 中的 toolchain 约束，防止构建节点误用旧 JDK。
