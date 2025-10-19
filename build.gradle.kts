plugins {
    id("com.android.application") version "8.4.1" apply false
    kotlin("android") version "1.9.24" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
