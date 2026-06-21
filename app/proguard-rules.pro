# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep JavascriptInterface annotations and classes used as javascript interfaces
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Explicitly keep MainActivity's WebAppInterface
-keep class com.example.MainActivity$WebAppInterface {
    public *;
}

# Preserve LineNumberTable and SourceFile properties for better crash recovery logs
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Optimize WebView and client classes
-keep public class * extends android.webkit.WebViewClient {
    public <init>();
    public *;
}
-keep public class * extends android.webkit.WebChromeClient {
    public <init>();
    public *;
}

# Keep the base helper model database class intact
-keep class com.example.MainActivity$IptvDatabaseHelper {
    public *;
}
-keep class com.example.MainActivity$IptvChannel {
    public *;
}
-keep class com.example.MainActivity$AsyncImageLoader {
    public *;
}
-keep class com.example.MainActivity$ChannelViewHolder {
    public *;
}

