# Primalite R8 rules (kept for when minification is enabled).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class androidx.webkit.** { *; }
-keep class com.pichillilorenzo.** { *; }
-dontwarn org.chromium.**
