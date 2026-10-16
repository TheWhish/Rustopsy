import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.jna)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "xyz.thewhish.rustopsy.MainKt"
        jvmArgs += listOf("-Xmx256m", "-Drustopsy.version=0.1.0-alpha")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Rustopsy"
            packageVersion = "0.1.0"
            modules("java.instrument", "jdk.unsupported", "java.net.http", "jdk.httpserver")

            windows {
                upgradeUuid = "049839ef-4b28-4048-b777-4219b93c5e8a"
                perUserInstall = true
                menu = true
                shortcut = true
            }
        }
    }
}
