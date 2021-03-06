@file:Suppress("UnstableApiUsage")

package dev.architectury.plugin

import dev.architectury.transformer.Transformer
import dev.architectury.transformer.input.OpenedOutputInterface
import dev.architectury.transformer.transformers.*
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.io.File
import java.nio.file.Path
import java.util.*
import java.util.function.Consumer
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

open class ArchitectPluginExtension(val project: Project) {
    var transformerVersion = "4.0.49"
    var injectablesVersion = "1.0.10"
    var minecraft = ""
    var injectInjectables = true
    var addCommonMarker = true
    private val transforms = mutableMapOf<String, Transform>()
    private var transformedLoom = false
    private val agentFile by lazy {
        project.gradle.rootProject.file(".gradle/architectury/architectury-transformer-agent.jar").also {
            it.parentFile.mkdirs()
        }
    }
    private val mainClassTransformerFile by lazy {
        project.file(".gradle/architectury/.main_class").also {
            it.parentFile.mkdirs()
        }
    }
    private val runtimeTransformerFile by lazy {
        project.file(".gradle/architectury/.transforms").also {
            it.parentFile.mkdirs()
        }
    }
    private val propertiesTransformerFile by lazy {
        project.file(".gradle/architectury/.properties").also {
            it.parentFile.mkdirs()
        }
    }

    init {
        project.afterEvaluate {
            if (transforms.isNotEmpty()) {
                val transformPaths = mutableMapOf<Path, List<Class<Transformer>>>()
                for (transform in transforms.values) {
                    project.configurations.getByName(transform.configName).forEach {
                        transformPaths[it.toPath()] = transform.transformers
                    }
                }
                transformPaths.asSequence().flatMap { it.value.asSequence().map { c -> it.key to c } }
                    .joinToString(File.pathSeparator) { "${it.first}|${it.second.name}" }
                    .also {
                        runtimeTransformerFile.writeText(it)
                    }

                val properties = Properties()
                properties(transforms.keys.first()).forEach { (key, value) ->
                    properties.setProperty(key, value)
                }
                propertiesTransformerFile.writer().use {
                    properties.store(it, "Architectury Runtime Transformer Properties")
                }
            }
        }
    }

    fun properties(platform: String): Map<String, String> {
        val loom = project.extensions.findByType(LoomGradleExtension::class.java) ?: return mapOf()
        return mutableMapOf(
            BuiltinProperties.MIXIN_MAPPINGS to loom.allMixinMappings.joinToString(File.pathSeparator),
            BuiltinProperties.INJECT_INJECTABLES to injectInjectables.toString(),
            BuiltinProperties.UNIQUE_IDENTIFIER to project.projectUniqueIdentifier(),
            BuiltinProperties.COMPILE_CLASSPATH to getCompileClasspath().joinToString(File.pathSeparator),
            BuiltinProperties.MAPPINGS_WITH_SRG to loom.tinyMappingsWithSrg.toString(),
            "architectury.platform.name" to platform,
            BuiltinProperties.REFMAP_NAME to loom.refmapName,
            BuiltinProperties.MCMETA_VERSION to "4"
        )
    }

    private val LoomGradleExtension.tinyMappingsWithSrg: Path
        get() {
            val mappingsProvider = LoomGradleExtension::class.java.getDeclaredMethod("getMappingsProvider").invoke(this)
            return mappingsProvider.javaClass.getField("tinyMappingsWithSrg").get(mappingsProvider) as Path
        }

    private fun getCompileClasspath(): Iterable<File> {
        return project.configurations.findByName("architecturyTransformerClasspath") ?: project.configurations.getByName("compileClasspath")
    }

    fun transform(name: String, action: Action<Transform>) {
        transforms.getOrPut(name) {
            Transform("development" + name.capitalize()).also { transform ->
                project.configurations.create(transform.configName)

                if (!transformedLoom) {
                    var plsAddInjectables = false
                    project.configurations.findByName("architecturyTransformerClasspath")
                        ?: project.configurations.create("architecturyTransformerClasspath") {
                            it.extendsFrom(project.configurations.getByName("compileClasspath"))
                            plsAddInjectables = true
                        }
                    val architecturyJavaAgents = project.configurations.create("architecturyJavaAgents") {
                        project.configurations.getByName("runtimeOnly").extendsFrom(it)
                    }
                    transformedLoom = true

                    with(project.dependencies) {
                        add("runtimeOnly", "dev.architectury:architectury-transformer:$transformerVersion:runtime")
                        add("architecturyJavaAgents", "dev.architectury:architectury-transformer:$transformerVersion:agent")
                        if (plsAddInjectables && injectInjectables) {
                            add("architecturyTransformerClasspath", "dev.architectury:architectury-injectables:$injectablesVersion")
                            add("architecturyTransformerClasspath", "net.fabricmc:fabric-loader:+")?.also {
                                it as ModuleDependency
                                it.isTransitive = false
                            }
                        }
                    }

                    val loom = project.extensions.getByType(LoomGradleExtension::class.java)
                    loom.settingsPostEdit.add(Consumer { config ->
                        val s = config.mainClass
                        config.mainClass = "dev.architectury.transformer.TransformerRuntime"
                        mainClassTransformerFile.writeText(s)
                        config.vmArgs += " -Darchitectury.main.class=${mainClassTransformerFile.absolutePath.escapeSpaces()}"
                        config.vmArgs += " -Darchitectury.runtime.transformer=${runtimeTransformerFile.absolutePath.escapeSpaces()}"
                        config.vmArgs += " -Darchitectury.properties=${propertiesTransformerFile.absolutePath.escapeSpaces()}"
                        config.vmArgs += " -Djdk.attach.allowAttachSelf=true"
                        if (architecturyJavaAgents.toList().size == 1) {
                            if (!agentFile.exists() || agentFile.delete()) {
                                architecturyJavaAgents.first().copyTo(agentFile, overwrite = true)
                            }
                            config.vmArgs += " -javaagent:${agentFile.absolutePath.escapeSpaces()}"
                        } else {
                            throw IllegalStateException(
                                "Illegal Count of Architectury Java Agents! " + architecturyJavaAgents.toList()
                                    .joinToString(", ")
                            )
                        }
                    })
                }
            }
        }.also {
            action.execute(it)
        }
    }

    private fun String.escapeSpaces(): String {
        if (any(Char::isWhitespace)) {
            return "\"$this\""
        }
        return this
    }

    @JvmOverloads
    fun fabric(action: Action<Transform> = Action {}) {
        transform("fabric", Action {
            it.setupFabricTransforms()
            action.execute(it)
        })
    }

    @JvmOverloads
    fun forge(action: Action<Transform> = Action {}) {
        transform("forge", Action {
            it.setupForgeTransforms()
            action.execute(it)
        })
    }

    fun common() {
        common {}
    }

    data class CommonSettings(
        var forgeEnabled: Boolean = true
    )

    fun platformSetupLoomIde() {
        val loomExtension = project.extensions.getByType(LoomGradleExtension::class.java)
        loomExtension.runConfigs.forEach { it.isIdeConfigGenerated = true }
        loomExtension.runConfigs.whenObjectAdded { it.isIdeConfigGenerated = true }
        loomExtension.addTaskBeforeRun("\$PROJECT_DIR\$/${project.name}:classes")
    }

    fun common(forgeEnabled: Boolean) {
        common {
            this.forgeEnabled = forgeEnabled
        }
    }

    fun common(action: CommonSettings.() -> Unit) {
        common(Action { it.action() })
    }

    fun common(action: Action<CommonSettings>) {
        val settings = CommonSettings().also { action.execute(it) }
        if (injectInjectables) {
            var plsAddInjectables = false
            project.configurations.findByName("architecturyTransformerClasspath")
                ?: project.configurations.create("architecturyTransformerClasspath") {
                    it.extendsFrom(project.configurations.getByName("compileClasspath"))
                    plsAddInjectables = true
                }

            with(project.dependencies) {
                add("compileOnly", "dev.architectury:architectury-injectables:$injectablesVersion")

                if (plsAddInjectables) {
                    add("architecturyTransformerClasspath", "dev.architectury:architectury-injectables:$injectablesVersion")
                    add("architecturyTransformerClasspath", "net.fabricmc:fabric-loader:+")?.also {
                        it as ModuleDependency
                        it.isTransitive = false
                    }
                }
            }
        }

        if (settings.forgeEnabled) {
            project.configurations.create("transformProductionForge")
        }
        project.configurations.create("transformProductionFabric")

        val buildTask = project.tasks.getByName("build")
        val jarTask = project.tasks.getByName("jar") {
            it as AbstractArchiveTask
            it.archiveClassifier.set("dev")
        } as AbstractArchiveTask

        val transformProductionFabricTask = project.tasks.getByName("transformProductionFabric") {
            it as TransformingTask

            it.archiveClassifier.set("transformProductionFabric")
            it.input.set(jarTask.archiveFile)

            project.artifacts.add("transformProductionFabric", it)
            it.dependsOn(jarTask)
            buildTask.dependsOn(it)
        } as TransformingTask

        val remapJarTask = project.tasks.getByName("remapJar") {
            it as RemapJarTask

            it.archiveClassifier.set("")
            it.input.set(jarTask.archiveFile)
            it.dependsOn(jarTask)
            it.doLast { _ ->
                if (addCommonMarker) {
                    val output = it.archiveFile.get().asFile

                    try {
                        OpenedOutputInterface.ofJar(output.toPath()).use { inter ->
                            inter.addFile("architectury.common.marker", "")
                        }
                    } catch (t: Throwable) {
                        project.logger.warn("Failed to add architectury.common.marker to ${output.absolutePath}")
                    }
                }
            }
        } as RemapJarTask

        if (settings.forgeEnabled) {
            val transformProductionForgeTask = project.tasks.getByName("transformProductionForge") {
                it as TransformingTask

                it.input.set(jarTask.archiveFile)
                it.archiveClassifier.set("transformProductionForge")

                project.artifacts.add("transformProductionForge", it)
                it.dependsOn(jarTask)
                buildTask.dependsOn(it)
            } as TransformingTask

            transformProductionForgeTask.archiveFile.get().asFile.takeUnless { it.exists() }?.createEmptyJar()

            project.extensions.getByType(LoomGradleExtension::class.java).generateSrgTiny = true
        }

        transformProductionFabricTask.archiveFile.get().asFile.takeUnless { it.exists() }?.createEmptyJar()
    }
}

private fun File.createEmptyJar() {
    parentFile.mkdirs()
    JarOutputStream(outputStream(), Manifest()).close()
}

data class Transform(val configName: String, val transformers: MutableList<Class<Transformer>> = mutableListOf()) {
    fun setupFabricTransforms() {
        this += RuntimeMixinRefmapDetector::class.java
        this += GenerateFakeFabricMod::class.java
        this += TransformExpectPlatform::class.java
        this += RemapInjectables::class.java
        this += TransformPlatformOnly::class.java
    }

    fun setupForgeTransforms() {
        this += RuntimeMixinRefmapDetector::class.java
        this += TransformExpectPlatform::class.java
        this += RemapInjectables::class.java
        this += TransformPlatformOnly::class.java

        this += TransformForgeAnnotations::class.java
        this += TransformForgeEnvironment::class.java
        this += GenerateFakeForgeMod::class.java
        this += FixForgeMixin::class.java
    }

    operator fun <T : Transformer> plusAssign(transformer: Class<T>) {
        transformers.add(transformer as Class<Transformer>)
    }

    fun <T : Transformer> add(transformer: Class<T>) = plusAssign(transformer as Class<Transformer>)
}