# R8 混淆规则

# ── 内核（gobind 生成，靠 JNI 按名字调用）──
# 这两个包一旦被改名，native 侧找不到类，进程直接崩。
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
-keep interface io.nekohasekai.libbox.** { *; }
-keepclassmembers class * implements io.nekohasekai.libbox.PlatformInterface { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── 节点参数按字段名写日志与读写 JSON ──
-keepclassmembers class com.leidian.sk5.SocksNode { *; }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

# 保留收缩与混淆，关闭激进优化：优化会重排 lambda/匿名类，
# 出问题时堆栈失真，而体积收益很小。
-dontoptimize
