import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
    // 引入 Kotlin 以便用 com.intellij.ui.dsl.builder 写面板（类型安全 builder，可读性 ×10）
    // 1.9.25 是兼容 JDK 17 / IntelliJ 2023.2 的稳定版本
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
}

group = "com.gitlab.pipeline.viewer"
version = "1.0.0"

repositories {
    // 国内镜像加速：依赖（如 gson 等）优先走阿里云
    maven("https://maven.aliyun.com/repository/public")
    maven("https://maven.aliyun.com/repository/central")
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

// Kotlin 与 Java 17 对齐：同一 JVM target，源码可被 Java 源互相引用
kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            // 与 Java 互操作：nullability 由注解描述；UI 层有大量 Java 互操作时不强制严格
            "-Xjsr305=strict"
        )
    }
}

// 资源文件（如 META-INF/plugin.xml）统一按 UTF-8 处理，避免中文 Windows（GBK 默认字符集）下
// 打包/校验时被按错误编码读取，导致 "invalid plugin descriptor" 报错
tasks.withType<ProcessResources>().configureEach {
    filteringCharset = "UTF-8"
}

// ---- 读取激活的构建 profile（与 settings.gradle.kts 逻辑保持一致）----
// 激活方式：-Pprofile=xxx > gradle.properties 的 gradle.profile > 默认 local
val activeProfile: String = providers.gradleProperty("profile").orNull
    ?: providers.gradleProperty("gradle.profile").orNull
    ?: "local"

// 读取 profile 文件：config/gradle-<profile>.properties
val profileProps = Properties().apply {
    val f = projectDir.resolve("config/gradle-$activeProfile.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// 当前 profile 配置的本地 IDE 目录（配置后跳过 IDE 发行包下载）
val ideLocalPath: String = profileProps.getProperty("ide.localPath") ?: ""

intellij {
    // 基于 2023.2 构建（Gradle 插件 1.17.4 官方支持的最后一个版本线），
    // sinceBuild=231 使其兼容 2023.1+ 的所有后续 IDEA 版本
    version.set("2023.2")
    type.set("IC")
    plugins.set(listOf("Git4Idea"))

    // 若当前 profile 配置了 ide.localPath（本地已解压的 IDEA），
    // 则直接使用本地 IDE，跳过 Gradle 下载 600MB 发行包（国内强烈建议配置）。
    // 国内镜像：https://download.jetbrains.com.cn/idea/ideaIC-2023.2.zip
    if (ideLocalPath.isNotBlank()) {
        localPath.set(ideLocalPath)
    }
}

tasks {
    patchPluginXml {
        sinceBuild.set("231")
        untilBuild.set("")
    }

    // ---- 本地调试 ----
    // 1. 默认方式：gradle runIde 会拉起一个带插件的新 IDEA 窗口，可直接点按钮验证功能。
    //
    // 2. 断点调试：取消下面注释后运行 gradle runIde，再用 IDEA 的 Remote JVM Debug
    //    连接 localhost:5005 即可断点调试插件代码。
    //    runIde {
    //        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005")
    //    }
}
