#!/usr/bin/env bash
# =====================================================================
# 构建入口脚本 —— 构建环境的「profile 激活器」
# ---------------------------------------------------------------------
# 作用：模拟 Spring Boot 通过 profile 切换不同构建环境。
#   1. 读取激活的 profile（命令行 -Pprofile=xxx 优先，否则 gradle.properties 的 gradle.profile）
#   2. 自动读取 config/gradle-<profile>.properties 中的 jdkHome，并通过
#      -Dorg.gradle.java.home 指定 Gradle daemon 的 JVM
#      （注意不能用 JAVA_HOME：在 mise 环境下 shim 会覆盖 JAVA_HOME，-D 参数不会被拦截；
#       且 Gradle 自带的 Kotlin 编译器不支持 JDK 25，必须用 JDK 17）
#   3. 把 profile 传给 gradlew（settings.gradle.kts 里会按 profile 加载代理 / IDE 路径 / JDK 校验）
#
# 注意：一律通过 ./gradlew（Gradle Wrapper）执行，版本锁定在 gradle/wrapper/gradle-wrapper.properties
#       （当前为 Gradle 8.5）。不要用系统/IDEA 自带的 gradle：
#       - IDEA 自带的 Gradle 9.x 与本项目插件不兼容（会报 Deprecated / API 缺失错误）；
#       - 系统 gradle 版本不可控，wrapper 保证所有人构建环境一致。
#
# 用法示例：
#   ./scripts/gradle.sh buildPlugin                    # 用默认 profile（local）
#   ./scripts/gradle.sh -Pprofile=sandbox buildPlugin  # 临时切到 sandbox
#   ./scripts/gradle.sh -Pprofile=local runIde         # 本地起调试 IDEA
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

# 0) 确保存在 Gradle Wrapper；首次使用会自动下载锁定的 Gradle 8.5
[ -x "./gradlew" ] || { echo "错误：缺少 gradlew，请重新运行 gradle wrapper 生成。"; exit 1; }

# 1) 解析 profile：命令行 -Pprofile=xxx 优先，否则读 gradle.properties
PROFILE=""
for arg in "$@"; do
  case "$arg" in
    -Pprofile=*) PROFILE="${arg#-Pprofile=}" ;;
  esac
done
[ -z "$PROFILE" ] && PROFILE=$(grep -E '^gradle.profile=' gradle.properties | cut -d= -f2 | tr -d ' ')
[ -z "$PROFILE" ] && PROFILE=local

PFILE="config/gradle-$PROFILE.properties"
[ -f "$PFILE" ] || { echo "错误：找不到 profile 配置文件 $PFILE"; exit 1; }

# 2) 若 profile 指定了 jdkHome，用 -Dorg.gradle.java.home 指定 Gradle daemon 的 JVM
JDK=$(grep -E '^jdkHome=' "$PFILE" | cut -d= -f2 | tr -d ' ')
if [ -n "$JDK" ] && [ -d "$JDK" ]; then
  echo ">>> profile=$PROFILE  Gradle JVM=$JDK"
  exec ./gradlew -Dorg.gradle.java.home="$JDK" -Pprofile="$PROFILE" "$@"
else
  echo ">>> profile=$PROFILE  使用系统默认 JVM"
  exec ./gradlew -Pprofile="$PROFILE" "$@"
fi
