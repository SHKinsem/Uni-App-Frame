pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        mavenLocal { content { includeGroupByRegex("com[.]tencent[.]shadow[.].*") } }
        google()
        mavenCentral()
    }
}
rootProject.name = "UniAppFrame"
include(":host")
