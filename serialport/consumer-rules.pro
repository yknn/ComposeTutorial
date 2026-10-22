# Keep the SerialPort JNI interface intact in consuming apps.
# The native library (libserial_port.so) resolves methods and the
# field "mFd" by name, so they must not be renamed or removed.
-keep class android.serialport.** { *; }
-keepclasseswithmembers class android.serialport.SerialPort {
    native <methods>;
}
