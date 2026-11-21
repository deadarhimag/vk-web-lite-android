# VK Web Lite

VK Web Lite — облегчённый Android-клиент для мобильной веб-версии ВКонтакте. Он открывает страницы VK в Android WebView и показывает отдельные разделы в нативном интерфейсе. Для доступа к личным разделам используется вход на странице VK.

## Возможности

- Лента, профили, друзья и сообщества, просмотр записей и комментариев.
- Список диалогов и переписка, поиск, вложения, голосовые сообщения и уведомления о новых сообщениях.
- Музыкальная очередь, фоновое воспроизведение и сохранение треков для прослушивания без сети.
- Просмотр видео и клипов в Media3 ExoPlayer, просмотр фотографий и альбомов, сохранение фотографий из альбома.
- Светлая и тёмная темы, системная тема и настройки навигации.

## Версия и совместимость

Версия **5.00.13**, код сборки **221**. Минимальная версия системы — Android 8.0 (API 26). Проект компилируется и ориентирован на Android 15 (API 35). APK собираются для `arm64-v8a` и `x86_64`, а также в универсальном варианте с обоими ABI. Подробности версий приведены в [истории изменений](CHANGELOG.md).

## Сборка

Понадобятся JDK 17, Android SDK Platform 35, Android SDK Build-Tools 34.0.0 и доступ к Google Maven, Maven Central и серверу дистрибутивов Gradle. Проект использует Kotlin 2.0.21, Android Gradle Plugin 8.6.1 и Gradle Wrapper 8.7. Устанавливать Gradle отдельно не требуется. В Windows разместите проект в каталоге с ASCII-путём, например `C:/projects/vk-web-lite-android`.

**Android Studio.** Откройте каталог с `settings.gradle.kts`, выберите JDK 17 в настройках Gradle и установите SDK через SDK Manager. После Gradle Sync выберите вариант `debug` и запустите модуль `app` на устройстве или эмуляторе.

**Командная строка.** Укажите путь к JDK 17 в `JAVA_HOME`, а путь к установленному Android SDK — в `ANDROID_HOME` или в локальном `local.properties` через свойство `sdk.dir`. Выполните из корня проекта:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

В Windows используйте `gradlew.bat :app:assembleDebug :app:testDebugUnitTest`. Отладочные APK появляются в `app/build/outputs/apk/debug/`. Для сборки с обычной отладочной подписью оставьте настройки релизного ключа незаданными. Параметры подписи перечислены в [шаблоне](keystore.properties.example) и в [руководстве сопровождения](docs/MAINTENANCE_RU.md#обычный-release-apk-в-android-studio).

Для тестов JavaScript установите Node.js (в CI используется версия 20) и npm, затем выполните `npm ci` и `npm test` из `tests/js/`. GitHub Actions запускает JS-тесты, JVM-тесты, Android Lint и сборку при push. Загрузка APK доступна через отдельный ручной запуск workflow.

## Структура проекта

| Путь | Содержание |
| --- | --- |
| `app/src/main/` | Kotlin-код, Android-ресурсы и скрипты JavaScript/CSS для WebView |
| `app/src/test/` | Модульные JVM-тесты JUnit |
| `tests/` | JS-тесты в jsdom и дополнительные проверки на JVM |
| `gradle/wrapper/` | Файлы Gradle Wrapper |
| `.github/` | GitHub Actions и Dependabot |
| `docs/` | Руководство сопровождения и заметки по версиям |

Основные библиотеки — AndroidX, Material Components, Media3, AndroidSVG и FFmpegKit Audio.

## Лицензия

В репозитории сохранён исходный файл [LICENSE](LICENSE) с текстом MIT. Сторонние компоненты распространяются на условиях своих лицензий. Сведения об AndroidX, Media3, FFmpegKit Audio, иконках Material и других зависимостях приведены в [уведомлениях](THIRD_PARTY_NOTICES.md).
