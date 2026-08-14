# Chaquopy keeps Python itself untouched by ProGuard/R8. If you enable
# minification later, add keep rules for com.chaquo.python.* and for any
# classes referenced from Python (none are in this scaffold).
-keep class com.chaquo.python.** { *; }
