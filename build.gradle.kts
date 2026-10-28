plugins {
    id("zenithproxy.plugin.dev") version "1.2.+"
}

val mc = property("mc") as String
val pluginId = property("plugin_id") as String
val zenithVersion = property("zenith_version") as String
// ZenithProxy for 1.21.x runs on Java 21, 26.x needs Java 25
val javaRelease = if (mc.startsWith("1.")) 21 else 25

group = property("maven_group") as String
// zenith only accepts x.y.z plugin versions, the MC version goes in the jar name instead
version = property("plugin_version") as String

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

zenithProxyPlugin {
    // Generates a BuildConstants.java file to the project's group java package
    // Allows you to pass in data from gradle into the plugin's classes
    buildConstants {
        // map of Java field name to String value
        fields = mapOf(
            "VERSION" to project.version.toString(),
            "MC_VERSION" to mc,
            "PLUGIN_ID" to pluginId,
            "MAVEN_GROUP" to project.group.toString(),
        )
    }
    // the minimum supported java version for users of your plugin
    javaReleaseVersion = JavaLanguageVersion.of(javaRelease)
    // set to false if developing against a zenith version before 3.7.0
    runTaskMixinLauncher = true
}

repositories {
    if (zenithVersion == "snapshot") {
        maven("https://maven.2b2t.vc/snapshots") {
            description = "ZenithProxy Prereleases"
        }
    }
    maven("https://maven.2b2t.vc/releases") {
        description = "ZenithProxy Releases"
    }
    maven("https://maven.2b2t.vc/remote") {
        description = "Dependencies used by ZenithProxy"
    }
}

dependencies {
    zenithProxy(
        if (zenithVersion == "snapshot") "com.zenith:ZenithProxy:$mc-SNAPSHOT"
        else "com.zenith:ZenithProxy:$zenithVersion+$mc"
    )

    /** to include dependencies into your plugin jar **/
//    shade("com.github.ben-manes.caffeine:caffeine:3.2.0")
}

tasks {
    shadowJar {
        // one jar per MC version, e.g. ZenithVoice-1.0.0+26.3.0.jar
        archiveVersion = "${project.version}+$mc"
        /**
         * relocate shaded dependencies to avoid conflicts with other plugins
         * transitive dependencies should also be relocated or removed (with exclude)
         * build and examine your plugin jar contents to check
         * https://gradleup.com/shadow/configuration/relocation/
         */
//        val basePackage = "${project.group}.shadow"
//        relocate("com.github.benmanes.caffeine", "$basePackage.caffeine")

        /**
         * remove unneeded transitive dependencies
         * https://gradleup.com/shadow/configuration/dependencies/#filtering-dependencies
         */
//        dependencies {
//            exclude(dependency(":error_prone_annotations:.*"))
//            exclude(dependency(":jspecify:.*"))
//        }
    }
}
