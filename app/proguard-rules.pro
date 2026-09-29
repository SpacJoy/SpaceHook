# ==================== SpaceHook 模块混淆规则 ====================

# --- libxposed 模块入口必须保留（框架按类名反射加载） ---
-keep class top.spacejoy.spacehook.libhook.SpaceHookEntry { public <init>(); }

# --- 所有 Hook 实现与框架类 ---
-keep class top.spacejoy.spacehook.libhook.** { *; }
-keep class top.spacejoy.spacehook.common.** { *; }

# --- libxposed API 运行时由框架提供 ---
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }

# --- 入口类被混淆后，重写 java_init.list 中的类名 ---
-adaptresourcefilecontents META-INF/xposed/java_init.list

# --- Compose ---
-dontwarn androidx.compose.**

# --- Kotlin ---
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
