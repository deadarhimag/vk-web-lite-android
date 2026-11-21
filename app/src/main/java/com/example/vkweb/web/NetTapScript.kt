package com.example.vkweb.web

import android.content.Context

/**
 * Общий перехват сетевых ответов страницы (assets/shared/net_tap.js). Подключается первым и в главный скрипт
 * ([PageScripts]), и в читатель сообщений ([com.example.vkweb.messages.ReaderScript]): он один на документ
 * (`window.__vkNetTap`), повторное внедрение ничего не ломает.
 */
object NetTapScript {
    const val ASSET = "shared/net_tap.js"

    /** Обезличивание ответов для дампа разработчика — общее у главного окна и окна чтения (3.68). */
    const val DUMP_ASSET = "shared/dump_core.js"

    const val COMPAT_ASSET = "shared/compat.js"

    fun source(context: Context): String = AssetText.read(context, COMPAT_ASSET) + "\n" +
        AssetText.read(context, ASSET) + "\n" + AssetText.read(context, DUMP_ASSET)
}
