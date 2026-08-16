# Hilt / Dagger generated code
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keepclasseswithmembers class * { @dagger.hilt.android.AndroidEntryPoint <init>(...); }

# Room entities are reflected over by the generated DAO implementations.
-keep class com.processlens.data.database.entity.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# Shizuku's provider is referenced from the manifest only.
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# Compose keeps its own rules via the AAR; retain @Composable metadata for stack traces.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Kotlin coroutines debug agent probes.
-dontwarn kotlinx.coroutines.debug.**
