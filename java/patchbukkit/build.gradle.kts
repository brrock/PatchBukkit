plugins {
    `java-library`
    id("com.google.protobuf") version "0.10.0"
    id("io.papermc.paperweight.userdev") version "2.0.0-SNAPSHOT"
}

val protobufVersion = "4.35.1"

sourceSets {
    main {
        proto {
            srcDir("../../proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }

    plugins {
        create("ffi") {
            path = "${rootProject.projectDir}/protoc-gen-ffi/build/libs/protoc-gen-ffi.jar"
        }
    }

    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("ffi")
            }
        }
    }
}

tasks.named("generateProto") {
    dependsOn(":protoc-gen-ffi:jar")
}

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "enginehub"
        url = uri("https://maven.enginehub.org/repo/")
    }
}

// Compat classes compiled against a plugin's API (e.g. WorldEdit). They ship inside
// patchbukkit.jar but are defined in the plugin's own classloader at runtime, so the
// plugin API itself must not be bundled: it stays off the main compileClasspath.
val worldedit: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}
val worldeditCompileOnly: Configuration by configurations.getting

dependencies {
    paperweight.paperDevBundle("26.3.build.8-alpha")
    implementation("net.sf.jopt-simple:jopt-simple:6.0-alpha-3")
    implementation("org.apache.maven:maven-resolver-provider:3.9.6")
    implementation("org.apache.maven.resolver:maven-resolver-impl:1.9.18")
    implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.18")
    implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.18")
    implementation("org.apache.maven.resolver:maven-resolver-util:1.9.18")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.26.0")
    implementation("commons-logging:commons-logging:1.3.5")
    implementation("commons-codec:commons-codec:1.18.0")
    implementation("commons-collections:commons-collections:3.2.2")
    implementation("net.bytebuddy:byte-buddy:1.15.11")
    implementation("net.bytebuddy:byte-buddy-agent:1.15.11")
    implementation("commons-lang:commons-lang:2.6")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("com.mysql:mysql-connector-j:9.1.0")
    worldeditCompileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.5") {
        isTransitive = false
    }
    worldeditCompileOnly("com.sk89q.worldedit:worldedit-core:7.4.5") {
        isTransitive = false
    }
    worldeditCompileOnly("com.sk89q.worldedit.worldedit-libs:core:7.4.5") {
        isTransitive = false
    }
    worldeditCompileOnly("org.enginehub.lin-bus:lin-bus-tree:0.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs("-Dnet.bytebuddy.experimental=true")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile> {
    options.isWarnings = false
    options.compilerArgs.addAll(listOf(
        "-Xlint:none",
        "-nowarn"
    ))
}

tasks.named<Jar>("jar") {
    isZip64 = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.compileClasspath)

    from(worldedit.output)

    from({
        configurations.compileClasspath.get().map { file ->
            if (file.isDirectory) file else zipTree(file)
        }
    })

    exclude(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA"
    )
}
