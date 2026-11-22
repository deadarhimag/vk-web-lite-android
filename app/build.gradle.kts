import java.util.Properties

plugins {
    id("com.android.application")
}

// Подпись релиза постоянным ключом. Значения — из keystore.properties в корне проекта (не в репозитории) или из
// переменных окружения VKWEB_* (так подписывает GitHub Actions, ключ — в Secrets). Нет ключа — релиз собирается
// неподписанным, как раньше, а отладочная сборка — со своим отладочным ключом.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    // UTF-8: пароль с русскими буквами иначе прочитался бы искажённым (по умолчанию — Latin-1).
    if (file.isFile) file.reader(Charsets.UTF_8).use { load(it) }
}

fun signingValue(name: String, env: String): String? =
    (keystoreProperties.getProperty(name) ?: System.getenv(env))?.trim()?.takeIf { it.isNotEmpty() }

val releaseStoreFile = signingValue("storeFile", "VKWEB_STORE_FILE")

android {
    namespace = "com.example.vkweb"
    // 35 (3.94): Google Play требует targetSdk 35; с ним Android 15 рисует приложение под системными панелями — это
    // учтено в ui/SystemBars.kt.
    compileSdk = 35

    defaultConfig {
        // Идентификатор приложения (3.94): свой, а не com.example (такой Google Play не принимает). Меняется только он —
        // пакеты в коде (namespace выше, папки com/example/vkweb) остаются: на установку они не влияют.
        // ВАЖНО: после смены это другое приложение — ставится рядом со старым, вход и сохранённое не переносятся.
        // Дальше не менять никогда: обновления ставятся только поверх того же идентификатора (и того же ключа).
        applicationId = "io.github.deadarhimag.vkweblite"
        minSdk = 26
        targetSdk = 35
        versionCode = 223
        versionName = "5.00.15"
    }

    signingConfigs {
        releaseStoreFile?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = signingValue("storePassword", "VKWEB_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "VKWEB_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "VKWEB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // R8 (3.94): лишний код и ресурсы библиотек удаляются — APK меньше, запуск быстрее. Имена классов не
            // меняются (-dontobfuscate в proguard-rules.pro): отчёты о сбое и журнал остаются читаемыми как раньше.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
        // Отладочная сборка (кнопка Run) — тем же ключом: она ставится поверх релизной и обратно без удаления
        // приложения, вход в VK и сохранённое не теряются.
        getByName("debug") {
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    // Размер APK (4.99.5). Почти весь вес — библиотеки FFmpegKit (сборка mp3): libavcodec, libavformat, libavfilter и
    // др., ~20 МБ на архитектуру; в APK их две (arm64-v8a и x86_64), и с minSdk 26 они лежат несжатыми.
    //  - Отдельный APK на архитектуру: телефону — app-arm64-v8a-*.apk (x86_64 нужен только эмулятору); общий
    //    app-universal-*.apk тоже собирается. 32-битных библиотек у FFmpegKit нет, поэтому и APK для armeabi-v7a нет.
    //  - Библиотеки в APK сжаты (useLegacyPackaging): файл заметно меньше, при установке Android их распакует
    //    (на телефоне они займут столько же, сколько раньше).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 4.78: версии — последние, что собираются с compileSdk 35 и AGP 8.6 (новее — compileSdk 36/36.1 и новый AGP:
    // отдельный шаг с проверкой на телефоне). Gradle сам сверяет minCompileSdk библиотек при сборке.
    implementation("androidx.core:core-ktx:1.16.0")
    // 1.10.1 — последняя перед переходом Activity на API 36 (1.11).
    implementation("androidx.activity:activity-ktx:1.10.1")
    // Не выше 1.14: с 1.15 webkit объявил API скрытия заголовка X-Requested-With ненужным (WebView снова шлёт
    // заголовок) — а им «Скрывать сведения об устройстве» не выдаёт VK имя пакета (WebViewSetup.hideDevice).
    implementation("androidx.webkit:webkit:1.14.0")
    // Проигрыватель видео и клипов (4.0): Media3 ExoPlayer вместо системного VideoView; HLS — для потоков «Авто».
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    // LGPL build: MP3 encoder, without the GPL-only FFmpeg components.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // Обновление потягиванием (4.52): стандартный жест списков Android.
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    // Плавные переходы (4.55): сдвиг «список ↔ переписка» (MaterialSharedAxis работает поверх androidx.transition).
    implementation("androidx.transition:transition:1.6.0")
    // Картинки реакций VK — SVG (отчёт 4.62: «46 шт., формат svg»); Android сам SVG не рисует (4.63).
    implementation("com.caverock:androidsvg-aar:1.4")

    testImplementation("junit:junit:4.13.2")
    // В unit-тестах android.jar — заглушка: JSONObject там бросает «Method ... not mocked». Без настоящей реализации
    // тесты разбора сообщений страницы (PayloadRoundTripTest, OwnerPayloadRoundTripTest и др.) падали бы в Gradle.
    testImplementation("org.json:json:20260814")
}
