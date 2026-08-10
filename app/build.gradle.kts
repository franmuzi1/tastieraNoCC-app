import com.android.build.api.variant.ApplicationVariant
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization") version "2.3.20"
    kotlin("plugin.compose") version "2.3.20"
}

// keyboard-cipher: firma delle release.
//
// La chiave NON sta nel repo e non ci deve entrare mai: il file di proprieta'
// e' in .gitignore e il percorso del keystore punta fuori dall'albero. Chi
// clona costruisce lo stesso, senza firma — vedi il buildType `release`.
//
// I valori si prendono da `keystore.properties` nella radice del progetto,
// oppure dalle variabili d'ambiente KC_KEYSTORE / KC_KEYSTORE_PASSWORD /
// KC_KEY_ALIAS / KC_KEY_PASSWORD, che e' la forma comoda per una CI.
//
// Sta fuori dal blocco `android { }` di proposito: li' dentro `java` e' la
// estensione Gradle e non il package, quindi `java.util.Properties` non si
// risolve.
val cipherKeystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun cipherSecret(name: String, env: String): String? =
    (cipherKeystoreProps.getProperty(name) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val cipherKeystoreFile = cipherSecret("storeFile", "KC_KEYSTORE")?.let { file(it) }

/** `false` se manca la chiave: il build prosegue e produce un APK non firmato. */
val cipherSigningReady: Boolean = cipherKeystoreFile?.exists() == true &&
        cipherSecret("storePassword", "KC_KEYSTORE_PASSWORD") != null &&
        cipherSecret("keyAlias", "KC_KEY_ALIAS") != null

android {
    compileSdk = 36

    defaultConfig {
        applicationId = "helium314.keyboard"
        minSdk = 21
        targetSdk = 36
        versionCode = 4006
        versionName = "4.0-dev1"
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }

    signingConfigs {
        if (cipherSigningReady) {
            create("cipherRelease") {
                storeFile = cipherKeystoreFile
                storePassword = cipherSecret("storePassword", "KC_KEYSTORE_PASSWORD")
                keyAlias = cipherSecret("keyAlias", "KC_KEY_ALIAS")
                // Se la password della chiave non c'e' si usa quella del
                // keystore: keytool le fa coincidere quando non se ne da' una
                // seconda, ed e' il caso normale.
                keyPassword = cipherSecret("keyPassword", "KC_KEY_PASSWORD")
                    ?: cipherSecret("storePassword", "KC_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
            // Senza chiave l'APK esce NON firmato invece di fallire: chi
            // clona il repo deve poterlo costruire, e un build che si rompe
            // per una chiave che non e' sua sarebbe un ostacolo senza scopo.
            // Un APK non firmato non si installa, quindi l'errore arriva
            // comunque — ma arriva dove si capisce cos'e'.
            if (cipherSigningReady) signingConfig = signingConfigs.getByName("cipherRelease")
        }
        create("nouserlib") { // same as release, but does not allow the user to provide a library
            isMinifyEnabled = true
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
            if (cipherSigningReady) signingConfig = signingConfigs.getByName("cipherRelease")
        }
        debug {
            // "normal" debug has minify for smaller APK to fit the GitHub 25 MB limit when zipped
            // and for better performance in case users want to install a debug APK
            isMinifyEnabled = true
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
        }
        create("runTests") { // build variant for running tests on CI that skips tests known to fail
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        create("debugNoMinify") { // for faster builds in IDE
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
        }

        androidComponents.onVariants { variant: ApplicationVariant ->
            if (variant.buildType == "debug") {
                // got a little too big for GitHub after some dependency upgrades, so we remove the largest dictionary
                variant.androidResources.ignoreAssetsPatterns = listOf("main_ro.dict")
                variant.proguardFiles = emptyList()
                //noinspection ProguardAndroidTxtUsage we intentionally use the "normal" file here
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/dontoptimize.pro"))
                variant.proguardFiles.add(project.layout.buildDirectory.file(project.buildFile.parent + "/proguard-rules.pro"))
            }
            variant.outputs.forEach { output ->
                if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                    output.outputFileName = "HeliBoard_${defaultConfig.versionName}-${variant.buildType}.apk"
                }
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    externalNativeBuild {
        ndkBuild {
            path = File("src/main/jni/Android.mk")
        }
    }
    ndkVersion = "28.0.13004108"

    // Il .so del core crypto non passa da ndkBuild: e' prodotto da cargo-ndk a
    // partire da un crate Rust che vive in un repo separato, e atterra in
    // jniLibs come libreria precompilata. La catena ndkBuild qui sopra resta
    // quella del dizionario nativo di HeliBoard, intatta.
    packaging {
        jniLibs {
            // shrinks APK by 3 MB, zipped size unchanged
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        target {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    // see https://github.com/HeliBorg/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
    }
}

dependencies {
    // androidx
    implementation("androidx.core:core-ktx:1.17.0") // 1.18.0 requires minSdk 23
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // newer than 2025.11.01 contains androidx.compose.material:material-android:1.10.0, which requires minSdk 23
    // maybe it's possible to use tools:overrideLibrary="androidx.compose.material" as it's not used explicitly, but probably this is just going to crash
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    "debugNoMinifyImplementation"("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0") // for easier re-ordering
    implementation("com.github.skydoves:colorpicker-compose:1.1.3") // for user-defined colors

    // keyboard-cipher: generazione del QR per lo scambio di persona.
    //
    // Solo il core di ZXing, che e' Java puro: niente codice nativo, niente
    // permessi, niente rete. Serve il SOLO encoder — la scansione
    // richiederebbe CAMERA, e quel permesso non si prende per una comodita'.
    //
    // Perche' una dipendenza invece di scriverlo in casa: un encoder QR e'
    // Reed-Solomon su GF(256) piu' la valutazione delle maschere, e un errore
    // sottile produce un codice che alcuni lettori accettano e altri no. E'
    // esattamente il tipo di bug che si scopre dall'altra parte del tavolo,
    // quando due persone stanno cercando di verificarsi a vicenda.
    implementation("com.google.zxing:core:3.5.3")

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.test:core:1.7.0")
}

// ============================================================================
// keyboard-cipher — core crypto in Rust
// ============================================================================

// Percorso del checkout di keyboard-cipher-core. Default: repo affiancato.
// Sovrascrivibile con -PcipherCorePath=/altro/percorso o in gradle.properties.
val cipherCorePath: String =
    (project.findProperty("cipherCorePath") as String?) ?: "../../tastieraNoCC"

// Le quattro ABI che Android usa oggi. Ognuna aggiunge circa 300 KB all'APK.
val cipherAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

// Tutto risolto QUI, a tempo di configurazione, e non dentro il task: la
// configuration cache serializza i task, e un riferimento allo script Gradle
// catturato in una lambda non e' serializzabile. Da qui in giu' si passano
// solo File e String.
val cipherCoreDir = file(cipherCorePath).resolve("jni")
val cipherLibsDir = file("src/main/jniLibs")
val cipherCoreAvailable = cipherCoreDir.resolve("Cargo.toml").exists()

// Rimappaggi dei percorsi sorgente, risolti qui a tempo di configurazione.
//
// Senza, il `.so` incide nella .rodata i percorsi assoluti della macchina che
// l'ha costruito: sono le stringhe di posizione dei `panic!` delle dipendenze
// (jni, curve25519-dalek, rand_core, cesu8, cipher), e restano anche dopo lo
// strip del debuginfo perche' non sono debuginfo.
//
// Due conseguenze, entrambe indesiderabili. La libreria rivela la struttura di
// chi compila — nome utente compreso. E soprattutto cambia a seconda di DOVE
// e' stata costruita, il che rende impossibile una build riproducibile: un
// binario che nessuno puo' ricostruire identico e' un binario di cui bisogna
// fidarsi sulla parola, che in un progetto di cifratura e' il contrario di
// cio' che serve.
val cipherCargoHome: String = System.getenv("CARGO_HOME")
    ?: "${System.getProperty("user.home")}/.cargo"
val cipherRustFlags: String = listOf(
    "--remap-path-prefix=$cipherCargoHome=/cargo",
    "--remap-path-prefix=${cipherCoreDir.absolutePath}=/src",
    "--remap-path-prefix=${cipherCoreDir.parentFile.absolutePath}=/src-core",
).joinToString(" ")

val buildCipherCore by tasks.registering(Exec::class) {
    group = "build"
    description = "Compila keyboard-cipher-jni per le ABI Android con cargo-ndk"

    workingDir = cipherCoreDir
    environment("RUSTFLAGS", cipherRustFlags)
    commandLine(
        buildList {
            add("cargo")
            add("ndk")
            cipherAbis.forEach { add("-t"); add(it) }
            add("-o"); add(cipherLibsDir.absolutePath)
            add("build")
            add("--release")
        }
    )

    // Se il core non e' affiancato il task e' disattivato e il build prosegue:
    // a fallire sara' il caricamento della libreria, dove il messaggio e'
    // comprensibile. Un build rotto per un percorso sbagliato sarebbe molto
    // piu' difficile da diagnosticare.
    //
    // `enabled` e non `onlyIf`: il secondo vuole una lambda, che verrebbe
    // catturata dalla configuration cache.
    enabled = cipherCoreAvailable
}

tasks.named("preBuild") {
    dependsOn(buildCipherCore)
}
