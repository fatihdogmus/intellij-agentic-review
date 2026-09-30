import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.JavaExec
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.kotlin.plugin.serialization")
    jacoco
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.3.0"))
    testImplementation(platform("org.jetbrains.kotlin:kotlin-bom:2.3.0"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.valiktor:valiktor-core:0.12.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testImplementation("org.junit.platform:junit-platform-launcher:1.12.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2026.1.1")
        bundledPlugin("Git4Idea")
        bundledPlugin("com.intellij.mcpServer")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("version")
        ideaVersion {
            sinceBuild = "253"
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2025.3")
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

fun Test.commonTestConfig() {
    useJUnitPlatform()
    testLogging {
        showStackTraces = true
        showExceptions = true
        showCauses = true
        showStandardStreams = true
        events("passed", "skipped", "failed", "standardOut", "standardError")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    jvmArgs(
        "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED",
        "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
    )
}

val jacocoExcludes = listOf(
    "dev.fatihdogmus.agenticreview.model.Review",
    "dev.fatihdogmus.agenticreview.model.SeenFileState",
    "dev.fatihdogmus.agenticreview.model.ReviewTarget",
    "dev.fatihdogmus.agenticreview.model.ReviewComment",
    "dev.fatihdogmus.agenticreview.model.CommentAnchor",
    "dev.fatihdogmus.agenticreview.model.AgentMetadata",
    "dev.fatihdogmus.agenticreview.model.ReviewStatus",
    "dev.fatihdogmus.agenticreview.model.CommentStatus",
    "dev.fatihdogmus.agenticreview.model.DiffSide",
    "dev.fatihdogmus.agenticreview.model.ReviewTargetType",
    "dev.fatihdogmus.agenticreview.model.ReviewModelsKt",
    "dev.fatihdogmus.agenticreview.model.CommentStatusSerializer",
    "dev.fatihdogmus.agenticreview.vcs.ChangedFile",
    "dev.fatihdogmus.agenticreview.vcs.ChangedFileStatus",
    "dev.fatihdogmus.agenticreview.vcs.ReviewContent",
    "dev.fatihdogmus.agenticreview.vcs.CommitMetadata",
    "dev.fatihdogmus.agenticreview.vcs.CommitPoint",
    "dev.fatihdogmus.agenticreview.vcs.GitCommandResult",
    "dev.fatihdogmus.agenticreview.vcs.CombinedCommitMetadata",
    "dev.fatihdogmus.agenticreview.vcs.ChangedFileModelsKt",
    "dev.fatihdogmus.agenticreview.vcs.BranchReviewMetadata",
    "dev.fatihdogmus.agenticreview.snapshot.TurnSnapshot",
    "dev.fatihdogmus.agenticreview.snapshot.TurnSnapshotResult",
    "dev.fatihdogmus.agenticreview.snapshot.TurnSnapshotSummary",
    "dev.fatihdogmus.agenticreview.snapshot.TurnToolCall",
    "dev.fatihdogmus.agenticreview.snapshot.TurnDiffState",
    "dev.fatihdogmus.agenticreview.snapshot.TurnSnapshotState",
    "dev.fatihdogmus.agenticreview.snapshot.TurnSnapshotListResult",
    "dev.fatihdogmus.agenticreview.persistence.ReviewState",
    "dev.fatihdogmus.agenticreview.persistence.ReviewSavePlan",
    "dev.fatihdogmus.agenticreview.persistence.ReviewLoadResult",
    "dev.fatihdogmus.agenticreview.mcp.ReviewDetails",
    "dev.fatihdogmus.agenticreview.mcp.CommentSummary",
    "dev.fatihdogmus.agenticreview.mcp.ReviewSummary",
    "dev.fatihdogmus.agenticreview.mcp.CommentAnchorPayload",
    "dev.fatihdogmus.agenticreview.mcp.ExportResult",
    "dev.fatihdogmus.agenticreview.mcp.MutationResult",
    "dev.fatihdogmus.agenticreview.mcp.ReviewReference",
    "dev.fatihdogmus.agenticreview.mcp.CommentListResult",
    "dev.fatihdogmus.agenticreview.mcp.ReviewResult",
    "dev.fatihdogmus.agenticreview.mcp.ReviewListResult",
    "dev.fatihdogmus.agenticreview.mcp.CommentContextResult",
    "dev.fatihdogmus.agenticreview.editor.ReviewPageVirtualFile",
    "dev.fatihdogmus.agenticreview.MalformedImportedReviewException",
    "dev.fatihdogmus.agenticreview.diff.ReviewDiffRequestData",
    "dev.fatihdogmus.agenticreview.diff.ReviewDiffRequestDataKt",
    "dev.fatihdogmus.agenticreview.diff.ReviewDiffPanel",
)

fun Test.applyJacoco() {
    extensions.configure(JacocoTaskExtension::class.java) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*") + jacocoExcludes
    }
}

tasks.named<Test>("test").configure {
    commonTestConfig()
    applyJacoco()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("instrumented/instrumentCode")) {
            jacocoExcludes.forEach { exclude(it) }
        }
    )
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(layout.buildDirectory.file("jacoco/test.exec"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.named<JavaExec>("runIde") {
    jvmArgs("-Dlocal.review.enable.mcp.by.default=true")
}
