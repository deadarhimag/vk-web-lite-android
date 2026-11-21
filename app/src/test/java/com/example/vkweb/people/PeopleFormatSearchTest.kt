package com.example.vkweb.people

import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleFormatSearchTest {

    private fun entry(name: String, city: String = "", activity: String = "") =
        PeopleEntry(id = 1L, kind = PeopleKind.FRIENDS, name = name, city = city, activity = activity)

    @Test fun `быстрый поиск даёт то же, что обычный`() {
        val people = listOf(entry("Алёна Петрова", "Москва"), entry("Иван Сидоров", "Казань", "Музыка"), entry("Пётр Иванов"))
        for (raw in listOf("алена", "ЕЛЕНА", "каз", "музыка", "иван", "я", "  петр ")) {
            val q = PeopleFormat.normalize(raw)
            assertEquals(raw, people.filter { PeopleFormat.matches(it, raw) }, people.filter { PeopleFormat.matchesNormalized(it, q) })
        }
    }
}
