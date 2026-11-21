package com.example.vkweb.messages

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import com.example.vkweb.R
import com.example.vkweb.diag.AppLog
import com.example.vkweb.diag.LogTag
import com.example.vkweb.ui.Notice

/**
 * Старые сообщения открытой переписки и переход к цитате (с 4.74 — отдельно от MessagesActivity).
 *
 *  - Догрузка: прокрутили к началу — страница догружает более старые; не чаще [THROTTLE_MS]; не пришли за
 *    [TIMEOUT_MS] — ожидание снимается. Меньше [FULL_PAGE_MIN] сообщений — это вся история, просить незачем.
 *  - Переход к сообщению, на которое ответили: есть в списке — прокрутка и подсветка; нет — догружаем старые, пока оно не
 *    появится (до [JUMP_MAX_ATTEMPTS] запросов) или пока не дойдём до начала переписки.
 */
class ChatHistoryPager(
    private val context: Context,
    private val handler: Handler,
    /** Открыта своя переписка (не список и не сайт VK). */
    private val chatShown: () -> Boolean,
    /** Сколько сообщений переписки пришло с сервера. */
    private val historySize: () -> Int,
    /** Сообщения на экране, в порядке показа. */
    private val shown: () -> List<ChatMessage>,
    /** Попросить страницу догрузить старые; итог — обычное обновление истории или [onStartReached]. */
    private val loadOlder: () -> Unit,
    /** Прокрутить к строке [index] и подсветить её. */
    private val scrollAndFlash: (index: Int) -> Unit,
) {
    /** Старые запрошены, ждём ответа. */
    var isLoading = false
        private set

    /** Переписка загружена с начала: старых больше нет. */
    private var startReached = false

    /** «Старые не запрашиваются» уже в журнале для этой переписки. */
    private var skipLogged = false
    private var lastRequest = 0L

    /** Сколько сообщений было, когда попросили старые: пришли ли они, видно по росту списка. */
    private var baseline = 0

    /** Сообщение, к которому перейти, когда оно догрузится сверху (нажали цитату ответа на старое сообщение). */
    private var pendingJump: ChatReply? = null
    private var jumpAttempts = 0

    /** Старше ничего нет (или страница не отозвалась) — ответа не будет: снимаем ожидание по времени. */
    private val release = Runnable {
        if (isLoading) AppLog.w(LogTag.MESSAGES, "старые сообщения не пришли", "было" to baseline, "мс" to TIMEOUT_MS)
        isLoading = false
        // Искали сообщение по цитате, а старые не пришли — говорим об этом, а не ждём молча (код ревью 3.51).
        if (pendingJump != null) {
            pendingJump = null
            Notice.show(context, R.string.reply_not_found)
        }
    }

    /** Открыта другая переписка: всё сначала. */
    fun reset() {
        startReached = false
        skipLogged = false
        pendingJump = null
        isLoading = false
        handler.removeCallbacks(release)
    }

    /** До начала переписки осталось [firstVisible] строк — пора просить старые. */
    fun isNearTop(firstVisible: Int): Boolean = firstVisible in 0..PRELOAD_ITEMS

    fun requestOlder() {
        if (!chatShown() || isLoading || startReached) return
        // Страница истории — 32 сообщения: если загружено меньше, истории глубже нет, просить старые незачем (раньше на
        // беседе из двух сообщений при каждом открытии шли запрос, восьмисекундное ожидание и предупреждение «не пришли»).
        if (historySize() < FULL_PAGE_MIN) {
            // Прокрутка вызывает это на каждом кадре: в журнал — один раз на переписку (отчёт: 12 одинаковых строк за 0,1 с).
            if (!skipLogged) AppLog.d(LogTag.MESSAGES, "старые сообщения не запрашиваются: вся история уже загружена", "сообщений" to historySize())
            skipLogged = true
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastRequest < THROTTLE_MS) return
        lastRequest = now
        isLoading = true
        baseline = historySize()
        AppLog.d(LogTag.MESSAGES, "запрошены старые сообщения", "было" to baseline)
        loadOlder()
        handler.removeCallbacks(release)
        handler.postDelayed(release, TIMEOUT_MS)
    }

    /** Страница сообщила: переписка загружена с самого начала. */
    fun onStartReached() {
        AppLog.i(LogTag.MESSAGES, "переписка загружена с самого начала", "сообщений" to historySize())
        startReached = true
        isLoading = false
        handler.removeCallbacks(release)
        pendingJump?.let { jumpTo(it) }
    }

    /**
     * Список на экране обновился; [grewAtTop] — сменилось первое сообщение. Ожидание старых снимается, только когда они
     * правда пришли (список вырос сверху), а не на любое обновление (статус отправки, новое сообщение внизу): иначе
     * следующий запрос уходил раньше, чем страница отвечала. Искали цитату — ищем снова, через [post] (после раскладки).
     */
    fun onListUpdated(grewAtTop: Boolean, post: (() -> Unit) -> Unit) {
        if (!isLoading || historySize() <= baseline || !grewAtTop) return
        AppLog.i(LogTag.MESSAGES, "догружены старые сообщения", "было" to baseline, "стало" to historySize())
        isLoading = false
        handler.removeCallbacks(release)
        pendingJump?.let { target -> post { jumpTo(target) } }
    }

    /** Перейти к сообщению, на которое ответили. */
    fun jumpTo(reply: ChatReply) {
        if (reply.cmid <= 0) return
        val index = shown().indexOfFirst { it.id == reply.messageId }
        if (index >= 0) {
            pendingJump = null
            AppLog.d(LogTag.MESSAGES, "переход к сообщению", "номер" to reply.cmid)
            scrollAndFlash(index)
            return
        }
        if (startReached || pendingJump?.cmid == reply.cmid && jumpAttempts >= JUMP_MAX_ATTEMPTS) {
            pendingJump = null
            Notice.show(context, R.string.reply_not_found)
            return
        }
        if (pendingJump?.cmid != reply.cmid) {
            jumpAttempts = 0
            Notice.show(context, R.string.reply_searching)
        }
        pendingJump = reply
        jumpAttempts++
        lastRequest = 0
        requestOlder()
        // Старые не запрошены (вся история уже здесь) — искать негде.
        if (!isLoading) {
            pendingJump = null
            Notice.show(context, R.string.reply_not_found)
        }
    }

    private companion object {
        const val THROTTLE_MS = 1_500L

        /** Сколько раз догружать старые в поисках сообщения, на которое ответили (по 100 за раз). */
        const val JUMP_MAX_ATTEMPTS = 20

        /** Меньше стольких сообщений — страница истории не заполнена, а значит, это вся история беседы. */
        const val FULL_PAGE_MIN = 30

        /** За сколько строк до начала переписки просить старые сообщения. */
        const val PRELOAD_ITEMS = 5
        const val TIMEOUT_MS = 8_000L
    }
}
