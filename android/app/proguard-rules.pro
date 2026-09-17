# ProGuard rules for the Koodo Reader Android host.
#
# The host is a thin WebView shell; the heavy logic lives in the bundled web
# build (assets/webapp), which is R8/ProGuard-invisible. These rules keep the
# small native bridge and standard WebView callbacks reachable.

# Keep the JavaScript bridge class (added via addJavascriptInterface).
-keep class com.koodoreader.reader.MainActivity$* {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Java interfaces annotated for JS.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep public class * extends android.webkit.WebViewClient
-keep public class * extends android.webkit.WebChromeClient

# Standard WebView / Kotlin coroutines (none used) — keep for safety.
-dontwarn org.json.**
-keep class org.json.** { *; }
