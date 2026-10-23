import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.habittracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.habittracker"
        minSdk = 28
        targetSdk = 35
        // Android Auto Backup이 더 새로운 DB 스키마를 구버전 APK에 복원하지 않도록 DB 버전과 함께 증가시킨다.
        versionCode = 35
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 실험 등록에 필요한 실제 소스를 같은 빌드의 자산으로 묶어 사용자의 JSON 준비를 없앤다.
val lotteryEvidencePaths = listOf(
    "app/build.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml",
    "app/src/main/java/com/habittracker/data/AppContainer.kt",
    "app/src/main/java/com/habittracker/data/repository/HabitRepository.kt",
    "app/src/main/java/com/habittracker/ui/components/DesignSystem.kt",
) + listOf(
    "LottoNumberGenerator", "LottoPriorDesignComparison", "LottoDesignRunStore", "LottoDesignRecordJson",
    "LottoExperimentStore", "LottoExperimentPolicy", "PensionExperimentStore", "PensionExperimentComparison",
    "LotteryAutoImprovement", "LotteryAutoImprovementWorker", "LotteryHistoryAudit", "LotteryDrawSyncModels",
    "LotteryAutomaticRegistration",
).map { "app/src/main/java/com/habittracker/data/lotto/$it.kt" } + listOf(
    "LottoViewModel", "LottoScreen", "LottoExperimentSection", "PensionLotteryGeneratorViewModel",
    "PensionLotteryGeneratorScreen", "PensionLotteryPerformanceAnalyzer", "PensionLotteryPerformanceSection",
    "PensionExperimentSection", "LotteryAutoImprovementSection",
).map { "app/src/main/java/com/habittracker/ui/lotto/$it.kt" }
val lotteryEvidenceAssets = layout.buildDirectory.dir("generated/lotteryExperimentAssets")
val evidenceKotlinVersion = libs.versions.kotlin.get()
val evidenceCoroutinesVersion = libs.versions.coroutines.get()
val prepareLotteryEvidence by tasks.registering(Sync::class) {
    into(lotteryEvidenceAssets.map { it.dir("lottery-experiment-source") })
    lotteryEvidencePaths.forEach { path ->
        from(rootProject.file(path)) { into(path.substringBeforeLast('/')) }
    }
    inputs.property("kotlinVersion", evidenceKotlinVersion)
    inputs.property("coroutinesVersion", evidenceCoroutinesVersion)
    doFirst {
        lotteryEvidencePaths.forEach { path ->
            require(rootProject.file(path).isFile) { "복권 실험 소스가 없습니다: $path" }
        }
    }
    doLast {
        destinationDir.resolve("dependencies.properties").writeText(
            "kotlin=$evidenceKotlinVersion\ncoroutines=$evidenceCoroutinesVersion\n",
        )
    }
}
android.sourceSets.getByName("main").assets.srcDir(lotteryEvidenceAssets)
tasks.named("preBuild") { dependsOn(prepareLotteryEvidence) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.google.material)
    implementation(libs.google.code.scanner)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.compose.foundation.layout)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation("androidx.test:runner:1.6.1")
}
