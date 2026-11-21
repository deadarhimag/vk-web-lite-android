package com.example.vkweb.owner

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.View

/**
 * Текст поста или описания со ссылками: упоминания [id1|Имя] — подписью-ссылкой ([OwnerFormat.segments]),
 * адреса в тексте — ссылками. Все нажатия уходят в [onLink], а не в браузер по умолчанию.
 */
object PostText {

    fun build(raw: String, onLink: (String) -> Unit): CharSequence {
        val out = SpannableStringBuilder()
        for (segment in OwnerFormat.segments(raw)) {
            val start = out.length
            out.append(segment.text)
            segment.link?.let { out.setSpan(LinkSpan(it, onLink), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        Linkify.addLinks(out, Linkify.WEB_URLS)
        // Linkify ставит URLSpan, который открыл бы браузер в обход приложения, — заменяем своим.
        for (span in out.getSpans(0, out.length, URLSpan::class.java)) {
            val start = out.getSpanStart(span)
            val end = out.getSpanEnd(span)
            out.removeSpan(span)
            out.setSpan(LinkSpan(span.url, onLink), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private class LinkSpan(private val target: String, private val onLink: (String) -> Unit) : ClickableSpan() {
        override fun onClick(widget: View) = onLink(target)

        override fun updateDrawState(ds: TextPaint) {
            ds.color = ds.linkColor
            ds.isUnderlineText = false
        }
    }
}
