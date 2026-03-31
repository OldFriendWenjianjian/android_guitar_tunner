/*
 * Top-level build file.
 *
 * 说明：这里不再放 Android 模块（`:app`）的构建脚本。
 * 原先放在本文件中的 `android { ... }` 与 `dependencies { ... }` 已迁移/保留在 `app/build.gradle.kts`，
 * 否则会导致根工程尝试应用 `com.android.application` 但缺少版本信息，从而无法解析插件。
 */
plugins {
    // Android Gradle Plugin (AGP)
    id("com.android.application") version "8.7.3" apply false

    // Kotlin
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
