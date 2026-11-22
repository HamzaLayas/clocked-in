import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

// Publishing secrets: real environment variables win, then the git-ignored `.env` file.
// See `.env.example`. Values are never printed.
val dotEnv: Map<String, String> = providers.fileContents(layout.projectDirectory.file(".env")).asText.orNull
    ?.lineSequence()
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
    ?.associate { line ->
        line.substringBefore("=").trim() to line.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
    }
    ?: emptyMap()

// The lambdas below capture only plain values, never the build script, so they work with
// the configuration cache.
fun secret(name: String): Provider<String> {
    val fromFile: String? = dotEnv[name]?.takeIf { it.isNotBlank() }
    return providers.environmentVariable(name).orElse(providers.provider { fromFile })
}

fun secretFile(name: String): Provider<RegularFile> {
    val projectDir = layout.projectDirectory
    return secret(name).map { projectDir.file(it) }
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Rely on the platform's JVM default methods instead of generating bridges to them.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("platformLocalPath").orNull
        if (localPath != null) {
            local(localPath)
        } else {
            create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion"))
        }
        bundledPlugin("com.intellij.mcpServer")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        changeNotes = """
            <ul>
              <li>All durations are shown as hh:mm:ss: worked and break time, totals, This Week,
                  notifications and the printed timesheet.</li>
              <li>Start and end times include seconds.</li>
              <li>CSV export has <code>break</code> and <code>worked</code> columns in hh:mm:ss; <code>hours</code> stays.</li>
              <li>Seconds are optional when editing a session.</li>
            </ul>
        """.trimIndent()
    }

    // Signing is optional. Use either the *_FILE paths (local) or the PEM contents (CI).
    signing {
        certificateChain = secret("CERTIFICATE_CHAIN")
        certificateChainFile = secretFile("CERTIFICATE_CHAIN_FILE")
        privateKey = secret("PRIVATE_KEY")
        privateKeyFile = secretFile("PRIVATE_KEY_FILE")
        password = secret("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = secret("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            // Every IDE release we support: the oldest (since-build) and the newest. A local IDE
            // (platformLocalPath) is verified too.
            val versions = providers.gradleProperty("verifyIdeVersions").orNull
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            versions.forEach { create(IntelliJPlatformType.IntellijIdea, it) }
            providers.gradleProperty("platformLocalPath").orNull?.let { local(file(it)) }
            if (versions.isEmpty() && providers.gradleProperty("platformLocalPath").orNull == null) recommended()
        }
    }
}

tasks.register("checkPublishingSecrets") {
    group = "publishing"
    description = "Reports which publishing secrets are set, without showing their values."
    val token = secret("PUBLISH_TOKEN").isPresent
    val chain = secret("CERTIFICATE_CHAIN").isPresent || secret("CERTIFICATE_CHAIN_FILE").isPresent
    val key = secret("PRIVATE_KEY").isPresent || secret("PRIVATE_KEY_FILE").isPresent
    val password = secret("PRIVATE_KEY_PASSWORD").isPresent
    val missingFiles = listOf("CERTIFICATE_CHAIN_FILE", "PRIVATE_KEY_FILE")
        .mapNotNull { name -> secret(name).orNull?.let { name to layout.projectDirectory.file(it).asFile } }
        .filter { !it.second.isFile }
        .map { "${it.first} points to a missing file: ${it.second}" }
    doLast {
        fun mark(ok: Boolean) = if (ok) "set" else "missing"
        println("PUBLISH_TOKEN         ${mark(token)}   (required for publishPlugin)")
        println("Certificate chain     ${mark(chain)}   (optional, for signing)")
        println("Private key           ${mark(key)}   (optional, for signing)")
        println("PRIVATE_KEY_PASSWORD  ${mark(password)}   (only if the private key is encrypted)")
        missingFiles.forEach { println("WARNING: $it") }
        if (chain != key) println("WARNING: signing needs both the certificate chain and the private key.")
        println(if (token) "Ready to publish." else "Not ready to publish: set PUBLISH_TOKEN in .env.")
    }
}

// ---- Installing and updating from GitHub releases ----
//
// The IDE can use any URL to an updatePlugins.xml as a custom plugin repository. Each GitHub
// release carries the plugin ZIP plus this file, and users add
//   https://github.com/<owner>/<repo>/releases/latest/download/updatePlugins.xml
// under Settings | Plugins | ⚙ | Manage Plugin Repositories.
val updatePluginsXml = tasks.register("updatePluginsXml") {
    group = "distribution"
    description = "Writes build/distributions/updatePlugins.xml for installing and updating from GitHub releases."
    val repository = providers.gradleProperty("githubRepository")
    val pluginId = "com.sterbenden.clockedin"
    val pluginVersion = providers.gradleProperty("pluginVersion")
    val pluginName = providers.gradleProperty("pluginName")
    val sinceBuild = providers.gradleProperty("pluginSinceBuild")
    val projectName = rootProject.name
    val archiveName = providers.gradleProperty("pluginVersion").map { "$projectName-$it.zip" }
    val notes = intellijPlatform.pluginConfiguration.changeNotes
    val output = layout.buildDirectory.file("distributions/updatePlugins.xml")
    inputs.property("repository", repository.orElse(""))
    inputs.property("version", pluginVersion)
    inputs.property("notes", notes.orElse(""))
    outputs.file(output)
    doLast {
        val repo = repository.orNull?.trim().orEmpty()
        if (!Regex("""[\w.-]+/[\w.-]+""").matches(repo)) {
            throw GradleException("Set githubRepository=owner/repo in gradle.properties (or pass -PgithubRepository=owner/repo).")
        }
        val version = pluginVersion.get()
        val url = "https://github.com/$repo/releases/download/v$version/${archiveName.get()}"
        output.get().asFile.writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<plugins>
            |  <plugin id="$pluginId" url="$url" version="$version">
            |    <idea-version since-build="${sinceBuild.get()}"/>
            |    <name>${pluginName.get()}</name>
            |    <vendor>SterbenDen</vendor>
            |    <change-notes><![CDATA[${notes.orNull.orEmpty()}]]></change-notes>
            |  </plugin>
            |</plugins>
            |""".trimMargin(),
        )
        println("Wrote ${output.get().asFile} for $url")
    }
}

tasks.register("githubReleaseFiles") {
    group = "distribution"
    description = "Builds the plugin ZIP and updatePlugins.xml to attach to a GitHub release."
    dependsOn(tasks.named("buildPlugin"), updatePluginsXml)
}
