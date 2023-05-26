import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.minecraftforge.gradle.common.util.RunConfig
import net.minecraftforge.gradle.mcp.tasks.GenerateSRG
import net.minecraftforge.srgutils.IMappingFile
import net.minecraftforge.srgutils.INamedMappingFile
import org.apache.tools.zip.ZipFile
import java.time.LocalDateTime


plugins {
    java
    `maven-publish`
    id("net.minecraftforge.gradle") version "5.1.+"
    id("com.github.johnrengelman.shadow") version "8.1.+" apply false
}

version = "1.0"
group = "dev.su5ed.connector"

val versionMc: String by project
val versionFabricLoader: String by project

val language by sourceSets.registering
val shadow: Configuration by configurations.creating
val yarnMappings: Configuration by configurations.creating
val depsJar: ShadowJar by tasks.creating(ShadowJar::class) {
    configurations = listOf(shadow)

    exclude("assets/fabricloader/**")
    exclude("META-INF/**")
    exclude("ui/**")
    exclude("*.json")

    dependencies {
        exclude(dependency("org.ow2.asm:"))
        exclude(dependency("net.sf.jopt-simple:"))
    }

    archiveClassifier.set("deps")
}
val languageJar: Jar by tasks.creating(Jar::class) {
    dependsOn("languageClasses")

    from(language.get().output)
    manifest.attributes("FMLModType" to "LANGPROVIDER")

    archiveClassifier.set("language")
}
val createObfToMcp by tasks.registering(GenerateSRG::class) {
    notch = true
    srg.set(tasks.extractSrg.flatMap { it.output })
    mappings.set(minecraft.mappings)
    format.set(IMappingFile.Format.TSRG)
}
val createYarnToMcp by tasks.registering(ConvertSRGTask::class) {
    inputYarnMappings.set(yarnMappings.singleFile)
    inputSrgMappings.set(createObfToMcp.flatMap { it.output })
    yarnTarget.set("named")
}
val createIntermediaryToSrg by tasks.registering(ConvertSRGTask::class) {
    inputYarnMappings.set(yarnMappings.singleFile)
    inputSrgMappings.set(tasks.extractSrg.flatMap { it.output })
    yarnTarget.set("intermediary")
}
val fullJar: Jar by tasks.creating(Jar::class) {
    from(zipTree(tasks.jar.flatMap { it.archiveFile }))
    from(zipTree(depsJar.archiveFile))
    from(languageJar)
    from(createYarnToMcp.flatMap { it.outputFile }) { rename { "yarnToMcp.tsrg" } }
    from(createIntermediaryToSrg.flatMap { it.outputFile }) { rename { "intermediaryToSrg.tsrg" } }
    manifest.attributes("Additional-Dependencies-Language" to languageJar.archiveFile.get().asFile.name)

    archiveClassifier.set("full")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

configurations {
    compileOnly {
        extendsFrom(shadow)
    }

    "languageImplementation" {
        extendsFrom(configurations.minecraft.get(), shadow)
    }
}

println("Java: ${System.getProperty("java.version")}, JVM: ${System.getProperty("java.vm.version")} (${System.getProperty("java.vendor")}), Arch: ${System.getProperty("os.arch")}")
minecraft {
    mappings("official", versionMc)

    // accessTransformer(file("src/main/resources/META-INF/accesstransformer.cfg"))

    runs {
        val config = Action<RunConfig> {
            property("forge.logging.console.level", "debug")
            property("forge.logging.markers", "REGISTRIES")
            workingDirectory = project.file("run").canonicalPath
            // Don't exit the daemon when the game closes
            forceExit = false

            mods {
                create("connector") {
                    sources(sourceSets.main.get())
                }
            }

            val existing = lazyTokens["minecraft_classpath"]
            lazyToken("minecraft_classpath") {
                fullJar.archiveFile.get().asFile.absolutePath
                    .let { path -> existing?.let { "$path;${it.get()}" } ?: path }
            }
        }

        create("client", config)
        create("server", config)

        create("data") {
            config(this)
            args(
                "--mod", "connector",
                "--all",
                "--output", file("src/generated/resources/"),
                "--existing", file("src/main/resources/")
            )
        }
    }
}

// Include resources generated by data generators.
sourceSets.main {
    resources {
        srcDir("src/generated/resources")
    }
}

repositories {
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net")
    }
    mavenLocal()
}

dependencies {
    minecraft(group = "net.minecraftforge", name = "forge", version = "$versionMc-45.0.64")

    shadow(group = "net.fabricmc", name = "fabric-loader", version = versionFabricLoader)
    // TODO Currently uses a local version with NPE fix on this line
    // https://github.com/MinecraftForge/ForgeAutoRenamingTool/blob/140befc9bf3e0ca5c8280c6d8e455ec01a916268/src/main/java/net/minecraftforge/fart/internal/EnhancedRemapper.java#L385
    shadow(group = "net.minecraftforge", name = "ForgeAutoRenamingTool", version = "1.0.2")
    yarnMappings(group = "net.fabricmc", name = "yarn", version = "1.19.4+build.2")
}

open class ConvertSRGTask : DefaultTask() {
    @get:InputFile
    val inputYarnMappings: RegularFileProperty = project.objects.fileProperty()

    @get:InputFile
    val inputSrgMappings: RegularFileProperty = project.objects.fileProperty()

    @get:Input
    val yarnTarget: Property<String> = project.objects.property<String>()

    @get:OutputFile
    val outputFile: RegularFileProperty = project.objects.fileProperty().convention(project.layout.buildDirectory.file("$name/output.tsrg"))

    @TaskAction
    fun execute() {
        val yarnMappings = ZipFile(inputYarnMappings.asFile.get()).use { zip ->
            val inputStream = zip.getInputStream(zip.getEntry("mappings/mappings.tiny"))
            INamedMappingFile.load(inputStream)
        }
        val obfToYarn = yarnMappings.getMap("official", yarnTarget.get())
        val obfToSrg = IMappingFile.load(inputSrgMappings.asFile.get())
        obfToYarn.reverse().chain(obfToSrg).write(outputFile.asFile.get().toPath(), IMappingFile.Format.TSRG, false)
    }
}

tasks {
    jar {
        finalizedBy("reobfJar")

        manifest {
            attributes(
                "Specification-Title" to project.name,
                "Specification-Vendor" to "Su5eD",
                "Specification-Version" to "1",
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "Implementation-Vendor" to "Su5eD",
                "Implementation-Timestamp" to LocalDateTime.now()
            )
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8" // Use the UTF-8 charset for Java compilation
    }

    configureEach {
        if (name == "prepareRuns") {
            dependsOn(fullJar)
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            suppressAllPomMetadataWarnings()

            from(components["java"])
        }
    }
}
