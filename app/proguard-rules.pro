# Enum constants are resolved from stored strings, so the names must survive.
# Every one of these round-trips through valueOf() when reading preferences.
-keepclassmembers enum com.teaglecode.focusphone.data.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# WorkManager instantiates workers reflectively from a class name.
-keep class com.teaglecode.focusphone.policy.PolicyWorker { <init>(...); }

# Components named in the manifest are kept by the manifest keep rules, but the
# accessibility service is also looked up by flattened component name when
# checking whether the user has switched it on.
-keep class com.teaglecode.focusphone.policy.FocusGuardService { *; }
-keep class com.teaglecode.focusphone.policy.FocusDeviceAdminReceiver { *; }
