# LSPosed API is compileOnly and resolved in the host/Xposed runtime.
-dontwarn io.github.libxposed.api.**

# Xposed module entry point loaded reflectively by libxposed.
-keep class com.originband.link.hook.EntryPoint {
    public <init>();
    public void onModuleLoaded(io.github.libxposed.api.XposedModuleInterface$ModuleLoadedParam);
    public void onPackageReady(io.github.libxposed.api.XposedModuleInterface$PackageReadyParam);
    public boolean onHotReloading(io.github.libxposed.api.XposedModuleInterface$HotReloadingParam);
    public void onHotReloaded(io.github.libxposed.api.XposedModuleInterface$HotReloadedParam);
}

# Xposed hook callback adapter and hook runtime helpers.
-keep class com.originband.link.hook.xposed.XC_MethodHook { *; }
-keep class com.originband.link.hook.xposed.XC_MethodHook$** { *; }
-keep class * extends com.originband.link.hook.xposed.XC_MethodHook { *; }
-keep class com.originband.link.hook.xposed.HookRuntime { *; }

# Root startup helper invoked via /system/bin/app_process.
-keep class com.originband.link.service.RootStartupGrant {
    public static void main(java.lang.String[]);
}

# Protobuf lite generated messages (reflection accessed by GeneratedMessageLite).
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Keep manifest-declared components and their constructors.
-keep class com.originband.link.BridgeApplication { *; }
-keep class * extends android.app.Activity { public <init>(...); }
-keep class * extends android.app.Service { public <init>(...); }
-keep class * extends android.content.BroadcastReceiver { public <init>(...); }
-keep class * extends android.content.ContentProvider { public <init>(...); }

# Strip Kotlin runtime parameter null-check bloat in release builds.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNullParameter(java.lang.Object, java.lang.String);
    public static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
}
