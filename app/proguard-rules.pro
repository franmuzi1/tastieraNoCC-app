# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# keyboard-cipher: il lato Rust scrive i campi di IncomingResult via JNI, con
# GetFieldID per nome. R8 non vede nessun lettore Kotlin di quei campi e li
# rimuoverebbe: -dontobfuscate conserva i nomi, non i membri inutilizzati. Il
# guasto sarebbe a runtime, dentro un'operazione crypto, e non al build.
-keep class helium314.keyboard.cipher.CipherCore { *; }
-keep class helium314.keyboard.cipher.CipherCore$IncomingResult { *; }

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate
