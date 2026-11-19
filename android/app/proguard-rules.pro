# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keepattributes SourceFile, LineNumberTable
-keep public class * extends java.lang.Exception
-keep class retrofit2.** { *; }

-keep class **.reflect.TypeToken { *; }
-keep class * extends **.reflect.TypeToken
# HomeMusic: Hub JSON models (Gson reflection) and the WebView JavaScript bridge
-keep class com.eddyizm.tempus.homemusic.Hub*Item { *; }
-keep class com.eddyizm.tempus.homemusic.HubMe { *; }
-keep class com.eddyizm.tempus.homemusic.HubAssistantTask { *; }
-keep class com.eddyizm.tempus.homemusic.HubUpload { *; }
-keep class com.eddyizm.tempus.homemusic.HubAppRelease { *; }
-keep class com.eddyizm.tempus.homemusic.HubUpdate* { *; }
-keep class com.eddyizm.tempus.homemusic.HubTrackSource { *; }
-keep class com.eddyizm.tempus.homemusic.HubPlayEventsResult { *; }
-keep class com.eddyizm.tempus.homemusic.HubNotifier$State { *; }
-keep class com.eddyizm.tempus.homemusic.PlayEvent { *; }
-keep class com.eddyizm.tempus.homemusic.HubFavoriteSnapshot { *; }
-keep class com.eddyizm.tempus.homemusic.HubFavoriteSubject { *; }
-keep class com.eddyizm.tempus.homemusic.HubLikedSongs { *; }
-keep class com.eddyizm.tempus.homemusic.HubLikedSong { *; }
-keep class com.eddyizm.tempus.homemusic.Wave* { *; }
-keep class com.eddyizm.tempus.homemusic.Smart* { *; }
-keep class com.eddyizm.tempus.homemusic.HubMix* { *; }
-keep class com.eddyizm.tempus.homemusic.HubTracks$Item { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.eddyizm.tempus.homemusic.HubAuthOptions { *; }
-keep class com.eddyizm.tempus.homemusic.HubGoogleLogin { *; }
# Sign-in with Google: Credential Manager finds its Play services provider by reflection
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }

# jbsdiff uses only bzip2; Commons Compress advertises optional codecs that are not bundled.
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
