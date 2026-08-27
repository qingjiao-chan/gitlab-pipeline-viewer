# GitLab Pipeline Viewer

> [![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

一个 IntelliJ IDEA 插件，让你在 IDE 侧边栏直接查看、触发和管理多个 GitLab 项目的 CI/CD 流水线，
无需切换浏览器即可完成「查看流水线状态 → 查看构建日志 → 触发 / 重试 / 取消」的完整闭环。

## 项目介绍

插件基于 **GitLab REST API v4** 实现，使用 `PRIVATE-TOKEN`（个人访问令牌）认证，
UI 采用 **Kotlin + IntelliJ DSL**（`com.intellij.ui.dsl.builder`）编写，通过 Gradle
（`org.jetbrains.intellij` 1.17.4）构建，目标平台为 IntelliJ IDEA 2023.2，
兼容 2023.1+ 的所有后续版本。

**核心功能：**

- **项目自动识别**：自动收集当前 IDEA 窗口内所有打开项目（含附加模块）的 Git 远程仓库；
  选择器第一项固定为「当前项目」，其后为 GitLab 项目组树（按需懒加载，不一次性拉全量项目）。
- **流水线列表**：展示状态、Ref、SHA、来源、创建时间、**耗时**与**触发人**（后两项通过并行拉取
  流水线详情补齐），支持分页浏览更早的历史流水线。
- **新建流水线**：弹窗选择分支（或手动输入），支持填写自定义 CI/CD 变量（`key=value`）。
- **取消 / 重试**：流水线级与 Job 级均支持；取消仅对运行中 / 等待中的生效，重试仅对失败 / 已取消的生效。
- **执行手动 Job**：可执行 `.gitlab-ci.yml` 中 `when: manual` 的作业，或重试失败 / 已取消的作业。
- **日志查看**：基于 IDEA Run 工具窗口同款 `ConsoleView` 展示构建日志——动态追加、ANSI 着色、
  进度行（`\r` 行首覆盖）语义保留；支持关键字搜索、高亮与上一处 / 下一处跳转。
- **增量日志（Range 轮询）**：日志经 HTTP `Range` 请求头增量拉取，每次只取上次偏移之后的新增部分
  追加到控制台（`206` 增量 / `200` 全量替换 / `416` 无新内容），不重复下载整份日志；
  分块边界若切断多字节 UTF-8 字符，自动拼接 carry 保证不乱码。
- **自动刷新**：仅当选中**运行中的流水线**且所选 Job 运行中时，周期轮询增量日志
  （流水线 / Job 列表不自动刷新），默认间隔 5s，可关闭或调整。
- **布局自适应**：工具窗口停靠侧边时上下分栏，停靠底部 / 顶部时自动切换为左右分栏。

**技术要点：**

- 数据流分层：UI 不直接接触 HTTP，统一经 `PipelineDataService` / `ProjectSelectionService` 取数，
  底层 `GitLabApiService` 对外只暴露领域实体，调用方无裸 JSON。
- 端点、查询参数、JSON 字段名全部收敛到 `GitLabEndpoints` / `GitLabFieldNames` 常量类，消灭魔法字符串。
- GET 只读接口带 TTL 缓存（项目 / 分支长缓存、流水线 / Job 列表短缓存），写操作后自动清空缓存。
- UI 用 Kotlin + `com.intellij.ui.dsl.builder` 编写，控件统一使用主题感知的 JB* 组件。
- 日志渲染走 IDEA 内置 `ConsoleView`（Run 工具窗口同款），大日志滚动不卡；搜索用
  Boyer-Moore-Horspool 算法 + 编辑器 MarkupModel 高亮，长日志搜索不卡 EDT。
- 并发防护：全局 generation 乐观锁丢弃过期响应；日志刷新以 `refreshInFlight` 防叠加、
  `traceOffset` 校验防止「增量与整体重建交错」导致的重复追加；用户取消后台任务不误报错误。
- 所有按钮带 loading（`AnimatedIcon` 旋转图标）与节流，日志区加载期间显示「正在加载…」占位层，
  避免连点与"点了没反应"的生硬感。

## 环境要求

- IntelliJ IDEA **2023.1 及以上**（`sinceBuild=231`）
- **JDK 17**（构建与运行；本插件使用的 IntelliJ Gradle 插件仅支持 JDK 11~17）
- 一个 GitLab 账号，且拥有目标项目的 API 访问权限（令牌需勾选 `api` scope）

## 项目结构

```
gitlab-pipeline-viewer/
├── build.gradle.kts            # 构建脚本（IntelliJ Gradle 插件 1.17.4，平台 2023.2；Kotlin 1.9.25）
├── settings.gradle.kts         # 构建环境 Profile 机制（代理注入 / JDK 版本校验）
├── gradle.properties           # 激活的 profile（默认 local）
├── gradlew / gradlew.bat       # Gradle Wrapper（锁定 Gradle 8.5）
├── gradle/wrapper/             # Wrapper 元数据与 gradle-wrapper.jar
├── config/                     # 各环境 profile 配置（gradle-local / gradle-sandbox：JDK / 代理 / 本地 IDE 路径）
├── scripts/gradle.sh           # Linux / CI 沙箱专用的命令行构建入口（Windows 本地在 IDEA 内构建，不需要）
├── .gitignore                  # Git 忽略规则
├── LICENSE / NOTICE / THIRD_PARTY_NOTICES.md   # 开源协议与第三方依赖声明
└── src/main/
    ├── java/com/gitlab/pipeline/viewer/
    │   ├── extension/          # 工具窗口扩展点（GitLabPipelineToolWindowFactory）
    │   ├── model/              # 领域实体
    │   │   ├── PipelineInfo / JobInfo / PipelineStatus    # 流水线 / Job / 状态枚举
    │   │   ├── GitLabProject / ProjectEntry / GroupEntry / GroupChildrenView   # 项目组树相关
    │   │   ├── BranchInfo      # 分支
    │   │   └── PipelineSnapshot / JobsView                # 数据流中间视图
    │   ├── services/           # 业务服务层
    │   │   ├── GitLabApiService          # GitLab REST v4 客户端（仓储层，TTL 缓存；Range 增量日志）
    │   │   ├── PipelineDataService       # 数据获取与编排（项目→流水线→Job→日志）
    │   │   ├── ProjectSelectionService   # 项目树选择的数据层（顶级组 / 子组 / 直接项目）
    │   │   ├── JobTraceResult            # 增量日志结果：内容 / 下一字节偏移 / UTF-8 carry / 全量标记
    │   │   ├── GitRepositoryUtil         # 收集窗口内所有项目的 Git 远程仓库
    │   │   ├── NotificationService       # 通知消息
    │   │   ├── GitLabEndpoints           # API 端点 / 参数常量
    │   │   ├── GitLabFieldNames          # JSON 字段名常量
    │   │   └── GitLabApiException        # API 异常（携带 HTTP 状态码）
    │   ├── settings/           # 配置持久化（GitLabSettings，存于 options/gitlab-pipeline-viewer.xml）
    │   └── util/               # 工具类（JsonUtil / GitUrlUtil）
    ├── kotlin/com/gitlab/pipeline/viewer/ui/     # UI 层（Kotlin + DSL builder）
    │   ├── GitLabPipelinePanel.kt     # 主面板：三栏 ActionToolbar / 流水线表格 / 自动刷新与并发调度
    │   ├── LogViewer.kt               # ConsoleView 日志视图：ANSI 解析 / BMH 搜索 / MarkupModel 高亮
    │   ├── ProjectTreeSelector.kt / JobSelector.kt   # 项目 / Job 选择器（JBPopup 弹层）
    │   ├── SettingsDialog.kt / TriggerPipelineDialog.kt   # 设置 / 触发流水线弹窗
    │   └── selector/                  # ChooseByNamePopup 相关：模型 / 渲染器 / 弹层控制器
    │       ├── ChooseByNamePopupController.kt
    │       ├── ProjectChooseByNameModel.kt / JobChooseByNameModel.kt
    │       └── RichListCellRenderers.kt
    └── resources/
        ├── META-INF/
        │   ├── plugin.xml      # 插件清单（工具窗口 / 通知组 / 配置项注册）
        │   └── pluginIcon.svg  # 插件 Logo（Settings → Plugins 列表中显示）
        └── icons/gitlab-pipeline.svg   # 工具窗口图标
```

## 构建与安装

项目使用 Gradle Wrapper（锁定 Gradle 8.5），国内环境已配置阿里云镜像。
Windows 本地开发**直接在 IDEA 内构建即可，不需要命令行**：

1. 用 IDEA 以 Gradle 项目方式打开 `gitlab-pipeline-viewer/` 目录。
2. 配置构建 JDK 为 **JDK 17**：`Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM`
   选择 JDK 17（`settings.gradle.kts` 会校验构建 JVM 版本必须在 11~17，用 JDK 25 等新版会直接报错并提示修复）。
3. 打开右侧 **Gradle** 工具窗口，展开 `gitlab-pipeline-viewer → Tasks → intellij`：
   - `buildPlugin`：构建插件，产物在 `build/distributions/` 下（`GitLabPipelineViewer-1.0.0.zip`）；
   - `runIde`：拉起一个带本插件的沙箱 IDEA 窗口，用于本地调试。
4. （可选）切换构建 profile：默认 `local`；需要切到 `sandbox`（走代理）时，
   修改 [gradle.properties](gradle.properties) 的 `gradle.profile` 为 `sandbox`，
   或在 Gradle 运行配置的参数里加 `-Pprofile=sandbox`。

> `scripts/gradle.sh` 仅面向 Linux / CI 沙箱等无 IDEA 管理的命令行环境（自动激活 JDK 与代理），Windows 本地用不到。

构建完成后，在 IDEA 中 `Settings → Plugins → 齿轮图标 → Install Plugin from Disk...`
选择 `build/distributions/GitLabPipelineViewer-1.0.0.zip` 安装并重启。

## 使用说明

1. 打开任意包含 Git 远程仓库的项目（远程地址需指向 GitLab，支持 `https` 与 `git@` / `ssh://` 形式）。
2. 底部 / 侧边工具窗口点击 **GitLab Pipelines** 打开面板。
3. 首次使用点击 **设置**，填写：
   - **GitLab 地址**：如 `https://gitlab.com` 或自建地址；
   - **访问令牌**：GitLab `Preferences → Access Tokens` 生成，**必须勾选 `api` 权限**；
   - 可选：自动刷新开关与刷新间隔、每页流水线条数、请求超时。
4. 项目选择器选目标项目（顶部「当前项目」或展开 GitLab 项目组树），下方即可看到流水线列表。

### 操作说明

| 操作 | 入口 | 说明 |
| --- | --- | --- |
| 刷新项目 | 刷新项目 | 重新收集窗口内项目并重建项目组树 |
| 刷新列表 | 刷新列表 | 重新加载当前项目当前页的流水线 |
| 翻页 | 上一页 / 下一页 | 分页浏览更早的流水线 |
| 新建流水线 | 新建流水线 | 弹窗选择 Ref、填写自定义变量后新建 |
| 取消流水线 | 取消流水线 | 仅运行 / 等待中的流水线可用 |
| 重试流水线 | 重试流水线 | 仅失败 / 已取消的流水线可用 |
| 执行 / 重试 Job | 执行 / 重试 | 执行 `when: manual` 作业；失败 / 已取消作业重试 |
| 取消 Job | 取消Job | 仅运行 / 等待中的 Job 可用 |
| 查看日志 | 点击 Job | ConsoleView 展示构建日志（动态追加、ANSI 着色、进度行保留） |
| 日志搜索 | 搜索框 + 上一处 / 下一处 | BMH 快速匹配，关键字高亮并跳转 |
| 刷新日志 | 刷新 | Range 增量拉取新增日志并追加；Job 结束自动全量替换 |

## 协议

本扩展以 **Apache License 2.0** 开源发布，完整条款见 [LICENSE](LICENSE)。

```text
Copyright 2026 qingjiao-chan
Licensed under the Apache License, Version 2.0
```
