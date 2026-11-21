# Controlled recovery checks

`RecoveryChecks.template` models the scheduler, HTTP result, publication and logging boundaries. The marked slots are populated with the **current production** `MusicDownloadStarter.cache`, `MusicDownloadStarter.finishRecovery` and `MusicCacheWorker.Job` source before compilation. Tests therefore exercise the controller code rather than a separately maintained copy of it.

The template is outside the Android Gradle source sets. It is not a WebView/network/FFmpeg emulator. Normal policy and operation-identity unit tests are in `app/src/test/java/com/example/vkweb/media/`; JavaScript operation and mutation checks run with `node review-checks.cjs`.
