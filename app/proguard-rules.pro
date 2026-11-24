# Keep line numbers for crash/telemetry stacks.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Protobuf (generated + reflective accessors used by the Android Auto messages). The generated
# classes are full-runtime GeneratedMessageV3 in com.z150x.app.aa.proto, which the package
# rule at the bottom keeps; this keeps the runtime's own reflective field access working.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageV3 {
  <fields>;
}
-dontwarn com.google.protobuf.**

# Conscrypt / TLS (AA self-mode).
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# ML Kit barcode / CameraX.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class androidx.camera.** { *; }

# App code + BuildConfig (kept whole: reflection-free, but log lines and stack traces stay readable).
-keep class com.z150x.app.** { *; }
