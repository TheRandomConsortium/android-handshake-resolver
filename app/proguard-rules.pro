# Keep native JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class org.handshake.resolver.engine.** { *; }
