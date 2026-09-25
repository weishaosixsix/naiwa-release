import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 正式签名配置放在仓库根的 keystore.properties（已 gitignore，含密码）。
// 文件不存在就回落到 debug 签名，这样刚 clone 下来的人不配置也能编译安装。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseStorePath = keystoreProps.getProperty("storeFile")
val hasReleaseSigning = !releaseStorePath.isNullOrBlank() && file(releaseStorePath).exists()

// 版本名单提出来：产物文件名要用，留在 defaultConfig 里会变成两处来源
val appVersionName = "1.1.0"

android {
    namespace = "com.sharkking.assistant"
    compileSdk = 34

    defaultConfig {
        // 安装身份。它与代码包名（上面的 namespace）是两回事：namespace 决定
        // 代码/R 类的位置，applicationId 才决定"这是哪个应用"。
        // 换掉它就等于换了一个新应用，可以和旧版共存、数据互不影响 ——
        // 所以改这个不用动任何源文件。
        applicationId = "com.huluwang.assistant"
        minSdk = 24
        targetSdk = 34
        versionCode = 100
        versionName = appVersionName

        // 更新检查指向的仓库。必须是自己发 release 的仓库：
        // 指向别人等于把自己的用户引导去装对方的包。
        // 该仓库还没有 release 时更新检查会静默失败，属正常，不是 bug。
        buildConfigField("String", "UPDATE_REPO", "\"weishaosixsix/naiwa-release\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 有正式密钥库就用它：debug 密钥跟机器绑定，换台机器编译出来的包装不上去
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// 产物文件名带应用名与版本。
// AGP 8.7 的公开 API（VariantOutput）只暴露 versionCode/versionName，没有输出
// 文件名，改不了 app-release.apk 这个名字；而用 applicationVariants 那套要去碰
// 内部类。所以另起一个任务把产物复制成好认的名字，不动变体自己的输出路径。
tasks.register<Copy>("distApk") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.buildDirectory.dir("dist"))
    rename { "鱼多多-$appVersionName.apk" }
    doLast { println("产物: build/dist/鱼多多-$appVersionName.apk") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Android 自带的 org.json 在 JVM 单测里是空壳（方法直接抛异常），
    // 换成真实实现才能验证 bin 的 info 字段解析
    testImplementation("org.json:json:20231013")
}
