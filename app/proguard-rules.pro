# Proguard rules for DTN-Android Messenger

# Room Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Koin DI
-keep class org.koin.** { *; }
-dontwarn org.koin.core.**

# Upokecenter CBOR
-keep class com.upokecenter.cbor.** { *; }
-dontwarn com.upokecenter.cbor.**

# Ktor Network
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.atomicfu.**
-dontwarn java.lang.management.**

# Android Auto Car App Library
-keep class androidx.car.app.** { *; }
-keep class * extends androidx.car.app.CarAppService
-keep class * extends androidx.car.app.Session
-keep class * extends androidx.car.app.Screen

# Kotlin Coroutines & Annotations
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn kotlinx.coroutines.**
-dontwarn org.slf4j.**
