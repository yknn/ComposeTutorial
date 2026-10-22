# Keep the JNI-bound class, native methods and the mFd field used by native code.
-keep class android.serialport.** { *; }
-keepclasseswithmembers class android.serialport.SerialPort {
    native <methods>;
}
