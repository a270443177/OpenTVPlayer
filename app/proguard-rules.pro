# OpenTV Player - R8 / ProGuard 规则

# 第三方库缺失类告警降噪（运行时不依赖，仅静态引用）
-dontwarn org.jetbrains.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn kotlinx.coroutines.**

# Media3 / ExoPlayer（AAR 自带 consumer rules，此处兜底）
-dontwarn androidx.media3.**

# 反射调用的可扩展渲染器需要保留
-keep class androidx.media3.exoplayer.DefaultRenderersFactory { *; }

# 移除 release 包中的调试日志，保留 w/e 便于现场排障
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
