# Release shrink rules for the R8 release build (validated on-device).
# Canonical keeps for this stack; tighten the final com.phonemcp catch-all only
# with a green device-matrix re-run.

# Kotlin runtime must never be renamed/stripped: startup + serialization look it up by name.
-keep class kotlin.** { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Hilt (consumer rules cover most; keep entry points explicit)
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.internal.ComponentManager { *; }
# javax.inject annotations + Provider/Lazy: referenced by Hilt-generated code at runtime.
-keep class javax.inject.** { *; }
# Guava ListenableFuture (standalone): referenced by test runner + CameraX futures on release.
-keep class com.google.common.util.concurrent.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-keep class kotlinx.serialization.** { *; }
-keepclasseswithmembernames class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor Netty engine + coroutines (reflective service loading)
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }

# CameraX ships consumer rules; keep our headless lifecycle wiring visible
-keep class androidx.camera.** { *; }

# Unreachable code paths whose artifacts are deliberately absent:
# HTTP/3 + QUIC (excluded from deps — server is HTTP/1.1-only) and JWT auth (not used).
-dontwarn io.netty.handler.codec.http3.**
-dontwarn io.netty.handler.codec.quic.**
-dontwarn io.ktor.server.auth.jwt.**
# Tomcat-native OpenSSL wrapper: Android uses Conscrypt/JDK SSL instead (unreachable).
-dontwarn io.netty.internal.tcnative.**
# Desktop/JDK-only APIs referenced but never executed on Android.
-dontwarn java.lang.management.**
-dontwarn javax.naming.ldap.**
-dontwarn jdk.jfr.**
# Logging backends Netty probes for but never uses on Android (JDK logging fallback wins).
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
# Full BouncyCastle (PEM parsing): never used — Android's built-in BC provider covers BKS.
-dontwarn org.bouncycastle.**
# Conscrypt: provided by the Android platform at runtime, absent from the compile classpath.
-dontwarn org.conscrypt.**
# Test runner needs it at startup although app code never references it.
-dontwarn androidx.tracing.**
-keep class androidx.tracing.** { *; }
# Test APK resolves these against the shrunk app at runtime (compose-test, ActivityScenario).
# compose-ui is kept whole: the test framework touches its internals package by package.
-keep class androidx.lifecycle.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.savedstate.** { *; }

# Netty instantiates channels reflectively (ReflectiveChannelFactory needs the no-arg ctor),
# and its config/allocator hierarchies must survive whole (partial keeps produce VerifyErrors).
-keep class io.netty.channel.** { *; }
-keep class io.netty.buffer.** { *; }
-keep class io.netty.bootstrap.** { *; }
-keep class io.netty.util.** { *; }
# Ktor-Netty wires the HTTP server pipeline (HttpServerCodec + aggregator + keep-alive)
# from kept engine code, but R8's Kotlin-2.2 metadata gaps break the reachability edge
# and strip every HttpServerCodec <init> (found 2026-10-13: release served TLS then
# killed all HTTP; usage.txt showed the gutted ctors). Keep the HTTP codec package
# whole — NOT all of handler.codec.** (its compression/marshalling codecs reference
# absent third-party jars and fail the build with missing-class errors).
-keep class io.netty.handler.codec.http.** { *; }
-keep class io.netty.handler.codec.* { *; }
-keep class io.ktor.server.netty.** { *; }
# Ktor-Netty negotiates h2 over ALPN whenever the client offers it (curl does),
# then serves HTTP/2 through this package. R8 gutted all 300+ http2 classes and
# the server answered GOAWAY+RST on every client (found 2026-10-13: release-only
# post-handshake death while openssl -alpn http/1.1 stayed green).
-keep class io.netty.handler.codec.http2.** { *; }
# Same story one layer down: R8 strips JdkAlpnApplicationProtocolNegotiator ctors,
# the server then accepts the client's first ALPN protocol (h2) into an
# HTTP/1.1-only pipeline and every client dies post-handshake. Keep TLS whole too.
-keep class io.netty.handler.ssl.** { *; }
# Netty's optional compression/marshalling codecs reference native jars absent on
# Android (brotli4j, zstd, jzlib, lz4, lzma, ning-compress, jboss-marshalling):
# unreachable from our pipeline — silence, never ship.
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.github.luben.zstd.**
-dontwarn com.jcraft.jzlib.**
-dontwarn net.jpountz.**
-dontwarn lzma.sdk.**
-dontwarn com.ning.compress.**
-dontwarn org.jboss.marshalling.**
# Netty's own test-only cert builders (pkitesting): referenced but never shipped.
-dontwarn io.netty.pkitesting.**
# Reactor BlockHound: Netty hooks it reflectively only when present (it isn't).
-dontwarn reactor.blockhound.**
# GraalVM substitutions + OSGi annotations inside netty-util: native-image-only, never on Android.
-dontwarn com.oracle.svm.**
-dontwarn org.osgi.**

# Broad but safe starting point for the app itself; tighten after verifying a shrunk build.
-keep class com.phonemcp.server.** { *; }
