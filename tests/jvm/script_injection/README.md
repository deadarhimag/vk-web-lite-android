# ScriptInjection callback checks

These checks compile the real `app/src/main/java/com/example/vkweb/web/ScriptInjection.kt` against the small boundary stubs in this directory. They exercise callback order, navigation, readiness and superseded requests. They do not emulate Android rendering or test the production URL policy.

With a Kotlin CLI installation and JDK, run from the project root:

```powershell
$injectionSources = Get-ChildItem tests/jvm/script_injection -Filter '*.kt' | ForEach-Object { $_.FullName }
kotlinc app/src/main/java/com/example/vkweb/web/ScriptInjection.kt @injectionSources -include-runtime -d injection-checks.jar
java -jar injection-checks.jar
```

The Android app's Gradle source sets do not include these stubs.
