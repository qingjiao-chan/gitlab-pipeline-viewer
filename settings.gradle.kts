rootProject.name = "gitlab-pipeline-viewer"

// 国内镜像加速：解析 Gradle 插件（如 org.jetbrains.intellij）时优先走阿里云
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
}

// =====================================================================
// 构建环境 Profile 机制（模拟 Spring Boot 的 spring.profiles.active）
// ---------------------------------------------------------------------
// 激活方式（优先级从高到低）：
//   1. 命令行  -Pprofile=sandbox
//   2. gradle.properties 里的 gradle.profile=local
//   3. 兜底     local
//
// 每个 profile 对应 config/gradle-<profile>.properties 文件，这里负责：
//   ① 代理配置：把 profile 中的 proxy.* 写入当前 daemon 的系统属性，
//      使后续插件下载 / 依赖下载 / IDE 发行包下载全部走代理；
//   ② JDK 校验：若 profile 指定了 jdkHome 而当前 daemon 的 JVM 不符，直接报错并给出修复命令。
// =====================================================================
val activeProfile: String = run {
    val cli = settings.providers.gradleProperty("profile").orNull        // -Pprofile=xxx
    val file = settings.providers.gradleProperty("gradle.profile").orNull // gradle.properties
    cli ?: file ?: "local"
}

// 读取 profile 属性文件（Java Properties 格式，注释以 # 开头，与 .properties 一致）
val profileProps = java.util.Properties().apply {
    val f = rootDir.resolve("config/gradle-$activeProfile.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    } else {
        throw GradleException("未找到 profile 配置文件：config/gradle-$activeProfile.properties")
    }
}

fun prop(name: String): String? = profileProps.getProperty(name)

// ① 代理：写入 JVM 系统属性（作用域为当前 daemon 进程，覆盖其后所有网络操作）
if (prop("proxy.enabled")?.toBoolean() == true) {
    System.setProperty("http.proxyHost", prop("http.proxyHost") ?: "")
    System.setProperty("http.proxyPort", prop("http.proxyPort") ?: "80")
    System.setProperty("https.proxyHost", prop("https.proxyHost") ?: "")
    System.setProperty("https.proxyPort", prop("https.proxyPort") ?: "443")
    prop("nonProxyHosts")?.let {
        System.setProperty("http.nonProxyHosts", it)
        System.setProperty("https.nonProxyHosts", it)
    }
}

// ② JDK 校验：只校验「版本是否在受支持范围」，不强比对路径。
//    为什么不强比对路径：同一台机器可能装了多个 JDK 17（如 C:\Program Files\... 与
//    C:\Users\<用户名>\.jdks\...），路径不同但都可用，强比对会造成误报。
//    真正致命的是「版本太新」：本插件（org.jetbrains.intellij 1.17.4）及其内置
//    Kotlin 编译器只支持 JDK 11~17，用 JDK 25 等新版会直接构建失败。
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val javaFeature = Runtime.version().feature()   // 当前构建 JVM 的主版本号，如 17 / 25
val jdkHome = prop("jdkHome")

// 2.1 版本硬校验：不在 11~17 直接报错，并按操作系统给出可用的修复命令
if (javaFeature < 11 || javaFeature > 17) {
    val fix = if (isWindows) {
        "  方法A（推荐）：IDEA → Settings → Build, Execution, Deployment → Build Tools → Gradle →\n" +
            "                 Gradle JVM 改为 JDK 17，再重新构建。\n" +
            "  方法B（命令行）：先执行  set JAVA_HOME=<你的JDK17目录>  （PowerShell 用 \$env:JAVA_HOME=\"<目录>\"），\n" +
            "                 再运行  .\\gradlew.bat -Pprofile=$activeProfile buildPlugin"
    } else {
        "  export JAVA_HOME=<你的JDK17目录>（如 /usr/lib/jvm/java-17-openjdk-amd64）\n" +
            "  然后运行  ./scripts/gradle.sh -Pprofile=$activeProfile buildPlugin"
    }
    throw GradleException(
        "当前构建 JVM 为 Java $javaFeature，但本插件要求 JDK 11~17。\n" +
            "请切换到 JDK 17 后重新构建：\n$fix"
    )
}

// 2.2 若 profile 配置了 jdkHome 但路径与当前 daemon 不一致：只提示、不阻断。
//     路径不同只要版本对就没问题（构建正确性只取决于版本）。
if (!jdkHome.isNullOrBlank()) {
    val currentHome = File(System.getProperty("java.home")).canonicalPath
    val configuredHome = File(jdkHome).canonicalPath
    if (!configuredHome.equals(currentHome, ignoreCase = isWindows)) {
        println(">>> 提示：profile 配置的 jdkHome（$configuredHome）与当前构建 JVM（$currentHome）不同，" +
            "已忽略路径差异（当前 Java 版本 $javaFeature 在支持范围 11~17 内）。")
    }
}

println(">>> 构建环境 profile = $activeProfile（由 scripts/gradle.sh 自动激活 JDK 与代理）")
