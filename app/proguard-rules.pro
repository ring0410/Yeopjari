# Pleos SDK(차량·내비·음성)와 그 보안 라이브러리는 이름을 바꾸거나 지우지 않음
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**
# 음성 SDK가 쓰는 JNA·Gson
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**
-keep class com.google.gson.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
# 안드로이드 자동차 라이브러리
-dontwarn android.car.**
