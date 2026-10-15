-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
# App classes are used by Gson/Retrofit through reflection (DTOs, API interface)
-keep class com.votera.app.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
