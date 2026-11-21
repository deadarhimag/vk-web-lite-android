package com.example.vkweb.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class StallReportTest {

    private fun e(cls: String, method: String, file: String, line: Int) = StackTraceElement(cls, method, file, line)

    @Test fun `верхний кадр и первые кадры приложения`() {
        val stack = arrayOf(
            e("android.os.BinderProxy", "transactNative", "BinderProxy.java", -2),
            e("android.view.View", "measure", "View.java", 100),
            e("com.example.vkweb.ui.ListUpdates", "submitAnchored", "ListUpdates.kt", 45),
            e("androidx.recyclerview.widget.RecyclerView", "onLayout", "RecyclerView.java", 9),
            e("com.example.vkweb.MainActivity", "onPayload", "MainActivity.kt", 700),
        )
        assertEquals(
            "BinderProxy.transactNative(BinderProxy.java:-2) ← ListUpdates.submitAnchored(ListUpdates.kt:45) ← MainActivity.onPayload(MainActivity.kt:700)",
            StallReport.describe(stack),
        )
    }

    @Test fun `пустой стек и кадры без файла`() {
        assertEquals("нет стека", StallReport.describe(emptyArray()))
        assertEquals("X.y(?:7)", StallReport.describe(arrayOf(StackTraceElement("a.b.X", "y", null, 7))))
    }

    @Test fun `не больше трёх кадров приложения`() {
        val stack = Array(10) { e("com.example.vkweb.A$it", "m", "A.kt", it) }
        assertEquals(4, StallReport.describe(stack).split(" ← ").size)
    }

    @Test fun `снимки одного подвисания — самые частые`() {
        val s = listOf("a", "b", "a", "a", "c", "b")
        assertEquals("a ×3 | b ×2", StallReport.summarize(s))
        assertEquals("", StallReport.summarize(emptyList()))
    }
}
