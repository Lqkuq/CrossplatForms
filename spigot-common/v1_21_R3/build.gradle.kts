
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.mojang:authlib:1.5.25") // see https://www.nathaan.com/explorer/?package=com.mojang&name=authlib
    api(projects.spigotCommon.v114R1)
}