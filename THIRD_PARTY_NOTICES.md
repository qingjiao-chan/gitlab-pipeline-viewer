# Third-Party Notices

本插件以 **Apache License 2.0** 开源（见 [LICENSE](LICENSE)）。项目直接使用的第三方组件及其许可见下表。

## 依赖类型说明

> 本插件为 IntelliJ IDEA 插件。打包产物 `build/distributions/*.zip` **不包含**任何第三方
> 二进制或 JAR：下面列出的运行时组件均由运行插件的 IDEA 自身提供（provided 作用域，
> 未在 `build.gradle.kts` 中声明为可分发依赖），构建期组件仅用于构建本机产物。
> 因此本项目对第三方组件的分发义务极轻，本文件仅用于逐项声明归属与许可证，并满足
> Apache 2.0 第 4(c)/4(d) 款在聚合/再分发时保留署名说明的要求。

## 运行时组件（由 JetBrains IDE 提供，不随插件发行）

| 组件 | 包/命名空间 | 许可证 | 用途 |
| --- | --- | --- | --- |
| IntelliJ Platform SDK | `com.intellij.*` | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 工具窗口、通知、进度任务、设置持久化等平台 API |
| JetBrains Annotations | `org.jetbrains.annotations.*` | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) | `@NotNull` / `@Nullable` 等编译期注解 |
| Gson | `com.google.gson.*` | [Apache License 2.0](https://github.com/google/gson/blob/main/LICENSE) | GitLab REST API 响应 JSON 解析 |
| Git4Idea（可选插件模块） | `git4idea.*` | [Apache License 2.0](https://github.com/JetBrains/intellij-community/blob/master/LICENSE.txt) | 读取当前项目 Git 远程仓库 |

## 构建期组件（仅用于构建本机产物，不随插件发行）

| 组件 | 版本 | 许可证 | 用途 |
| --- | --- | --- | --- |
| Gradle Wrapper | 8.5 | [Apache License 2.0](https://github.com/gradle/gradle/blob/master/LICENSE) | 构建工具 |
| org.jetbrains.intellij Gradle 插件 | 1.17.4 | [Apache License 2.0](https://github.com/JetBrains/gradle-intellij-plugin/blob/master/LICENSE) | IntelliJ 插件构建插件 |

## 许可证副本

Apache License 2.0 全文见 [LICENSE](LICENSE)。

## 商标说明

- **IntelliJ IDEA** 是 JetBrains s.r.o. 的注册商标；
- **GitLab** 是 GitLab Inc. 的商标。

本项目为独立的第三方扩展，与 JetBrains s.r.o.、GitLab Inc. 无任何隶属、背书或赞助关系。<br>
项目名称与描述中对上述名称的引用，均为指明其兼容平台/集成目标的合理描述性使用（Apache License 2.0 第 6 款）。