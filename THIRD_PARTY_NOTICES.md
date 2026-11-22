# Сторонние компоненты

Лицензии ниже относятся к соответствующим компонентам. Версии прямых зависимостей определены в [app/build.gradle.kts](app/build.gradle.kts), зависимости тестов JavaScript зафиксированы в [package-lock.json](tests/js/package-lock.json).

| Компонент | Лицензия и источник |
| --- | --- |
| Kotlin 2.4.21 и Gradle Wrapper 9.6.0 | Apache 2.0. [Kotlin](https://github.com/JetBrains/kotlin/blob/v2.4.21/license/LICENSE.txt), [Gradle](https://github.com/gradle/gradle/blob/v9.6.0/LICENSE) |
| AndroidX (Core, Activity, WebKit, AppCompat, Preference, RecyclerView, SwipeRefreshLayout и Transition) и Media3 1.5.1 | Apache 2.0. [AndroidX](https://github.com/androidx/androidx), [Media3](https://github.com/androidx/media) |
| Material Components 1.14.0 и AndroidSVG 1.4 | Apache 2.0. [Material Components](https://github.com/material-components/material-components-android/blob/1.14.0/LICENSE), [AndroidSVG](https://github.com/BigBadaboom/androidsvg/blob/master/LICENSE) |
| JUnit 4.13.2, JSON-java 20260814 и jsdom 24.1.3 | Тестовые зависимости. [JUnit 4 (EPL 1.0)](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt), [JSON-java (Public Domain)](https://github.com/stleary/JSON-java/blob/20260814/LICENSE), [jsdom (MIT)](https://github.com/jsdom/jsdom/blob/v24.1.3/LICENSE.txt) |

## Material Icons и Material Symbols

Ресурсы `app/src/main/res/drawable/` с пометками Material Icons и Material Symbols используют графику из [Google Material Design Icons](https://github.com/google/material-design-icons), преобразованную в Android VectorDrawable. Атрибуция сохранена в XML. [Текст Apache License 2.0](app/src/main/assets/licenses/Apache-2.0.txt) включён в исходники и ресурсы приложения.

## FFmpegKit Audio 8.1.9

Зависимость `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.9` применяется для кодирования MP3 и обработки локальных HLS-плейлистов. Артефакт Audio заявлен под LGPL 3.0 в [метаданных Maven Central](https://repo.maven.apache.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-audio/8.1.9/ffmpeg-kit-audio-8.1.9.pom). В настройках приложения доступен [текст LGPL 3.0 вместе с GPL 3.0](app/src/main/assets/licenses/LGPL-3.0.txt). Код проекта получает AAR при сборке. В архив включены нативные библиотеки для `arm64-v8a` и `x86_64`, в том числе MP3-кодировщик LAME.

Перед распространением APK следует подтвердить соответствие исходников из [репозитория поставщика](https://github.com/ffmpegkit-maintained/ffmpeg/tree/v8.1.9-lts-android) конкретному AAR Audio, доступность соответствующих исходников и выполнение условий [LGPL 3.0](https://www.gnu.org/licenses/lgpl-3.0.html) для готовой сборки.
