package com.example.vkweb.code

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Сбой 4.61: `listOf(findViewById(R.id.кнопка), поле, …)` — Kotlin выводит тип списка из поля (EditText), подставляет его
 * в findViewById без явного типа, и кнопка «становится» EditText: ArrayStoreException при открытии экрана. Компилятор
 * это пропускает, поэтому проверка — по исходникам: findViewById без типа в списке вместе с чем-то другим нельзя.
 */
class FindViewTypesTest {

    /** Тесты запускаются из app/ (Gradle) или из корня проекта. */
    private val sources = sequenceOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun `findViewById без типа не смешивается в списке с другими видами`() {
        val untyped = Regex("""findViewById\(R\.""")
        val offenders = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val start = line.indexOf("listOf(")
                if (start < 0 || !untyped.containsMatchIn(line)) return@mapIndexedNotNull null
                val args = line.substring(start + "listOf(".length).substringBeforeLast(")").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (args.all { it.startsWith("findViewById(") }) null else "${file.name}:${i + 1}"
            }
        }
        assertEquals("Явный тип: findViewById<View>(…) или listOf<View>(…)", emptyList<String>(), offenders)
    }
}
