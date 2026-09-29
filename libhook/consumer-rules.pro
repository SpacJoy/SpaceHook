# SpaceHook libhook —— 混淆保护规则
# Hook 实现类由 libxposed 框架按类名反射加载，必须保留。

# 模块入口
-keep class top.spacejoy.spacehook.libhook.SpaceHookEntry { public <init>(); }

# 所有 Hook 加载器与实现
-keep class top.spacejoy.spacehook.libhook.** { *; }

# libxposed API 在运行时由框架提供
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.api.** { *; }
