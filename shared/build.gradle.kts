plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin { androidTarget(); jvmToolchain(21) }

android { namespace = "com.taotao.music.shared"; compileSdk = 35 }
