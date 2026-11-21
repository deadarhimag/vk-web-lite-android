# R8 для релизной сборки (3.94).

# Имена классов и методов не меняются: отчёт о сбое (crash/CrashLog.kt) и журнал показывают их как есть, расшифровка
# через mapping.txt не нужна. Удаление неиспользуемого кода и ресурсов при этом работает.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Поиск в меню экрана сообщений создаётся по имени класса из res/menu/messages_toolbar.xml (app:actionViewClass).
-keep class androidx.appcompat.widget.SearchView { <init>(...); }

# Свои View из разметки и компоненты из манифеста AAPT сохраняет сам; рефлексии в коде приложения нет.

# Свой ползунок настроек (4.24) создаётся по имени класса из res/xml/preferences.xml. Конструктор (Context, AttributeSet)
# сохраняют и правила библиотеки preference; здесь — явно, чтобы экран настроек не зависел от их состава.
-keep class com.example.vkweb.settings.StepSeekBarPreference { <init>(...); }

# FFmpegKit (4.95): нативная библиотека вызывает методы FFmpegKitConfig (журнал, статистика, SAF) из JNI по имени —
# R8 не видит этих вызовов и мог бы удалить методы. Правила поставки форка это покрывают; здесь — явно, на случай их смены.
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
