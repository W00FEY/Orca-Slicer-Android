# JNI entry points are looked up by name from native code.
-keep class io.github.w00fey.orcaslicer.engine.NativeEngine { native <methods>; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
