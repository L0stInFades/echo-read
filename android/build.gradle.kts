// 根工程只声明插件版本（apply false），具体应用在 app/ 模块
plugins {
    alias(libs.plugins.android.application) apply false
    // 不在任何模块里应用（AGP 9 内置了 Kotlin 支持）；声明在这里只为把 Kotlin Gradle 插件钉在 libs 里的版本 ——
    // 否则用的是 AGP 自带依赖的旧版（2.2.10），与 compose 编译器插件的版本对不上
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}
