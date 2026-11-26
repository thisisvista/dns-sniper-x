# Keep the generated gomobile bridge API and Go runtime entry points.
# R8 can still shrink unrelated app/dependency code and resources.
-keep class mobile.** { *; }
-keep class go.** { *; }

# Preserve JNI method names/signatures used by native libraries.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Room generates implementations at compile time; keep runtime annotations.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
