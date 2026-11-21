package com.example.vkweb.media

import com.example.vkweb.config.SafeUrls
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.StringReader
import java.net.URI
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil

/** A finite audio-only DASH plan. No network access while parsing the manifest. */
internal object DashAudioPlan {
    class Failure(val reason: String) : IllegalStateException("DASH: $reason")
    data class Resource(val url: String, val range: LongRange? = null)
    data class Plan(val resources: List<Resource>)
    const val MAX_RESOURCES = 10_000
    const val MAX_XML_CHARS = 2 * 1024 * 1024

    fun parse(xml: String, manifestUrl: String): Plan {
        if (xml.length > MAX_XML_CHARS) fail("manifest_limit")
        // Reject DTD/entity declarations before any XML implementation sees the document.
        if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) fail("xml_declaration")
        val root = try {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw SAXException("External entities are disabled") }
            builder.parse(InputSource(StringReader(xml.removePrefix("\uFEFF")))).documentElement
        } catch (_: Exception) { fail("invalid_xml") }
        if (root.name() != "MPD") fail("not_mpd")
        if (root.getAttribute("type").let { it.isNotEmpty() && it != "static" }) fail("dynamic_manifest")
        val periods = root.children("Period")
        if (periods.size != 1) fail("period_count")
        val period = periods.single()
        val candidates = period.children("AdaptationSet").flatMap { adaptation ->
            adaptation.children("Representation").mapNotNull { rep ->
                val mime = rep.attrOr(adaptation, "mimeType").lowercase()
                val content = rep.attrOr(adaptation, "contentType").lowercase()
                if (mime.startsWith("audio/") || content == "audio") adaptation to rep else null
            }
        }.sortedByDescending { it.second.getAttribute("bandwidth").toLongOrNull() ?: 0L }
        if (candidates.isEmpty()) fail("no_audio")
        // Prefer the first unencrypted audio representation; do not fall back to a video representation.
        val selected = candidates.firstOrNull { (adaptation, rep) ->
            listOf(root, period, adaptation, rep).none { it.children("ContentProtection").isNotEmpty() }
        } ?: fail("protected_audio")
        val path = listOf(root, period, selected.first, selected.second)
        var base = safe(manifestUrl, "")
        var explicitBase = false
        path.forEach { node ->
            val bases = node.children("BaseURL")
            if (bases.isNotEmpty()) {
                val relative = bases.first().textContent.trim()
                if (relative.isEmpty()) fail("empty_base")
                base = safe(base, relative); explicitBase = true
            }
        }
        val address = path.asReversed().firstNotNullOfOrNull { node ->
            node.children().firstOrNull { it.name() in setOf("SegmentBase", "SegmentList", "SegmentTemplate") }
        }
        if (address == null || address.name() == "SegmentBase") {
            if (!explicitBase) fail("missing_audio_url")
            val initialization = address?.children("Initialization")?.firstOrNull()
            val separate = initialization?.getAttribute("sourceURL").orEmpty()
            if (separate.isNotEmpty() && safe(base, separate) != base) fail("separate_segment_base_init")
            // SegmentBase describes ranges of a complete representation; GET of its BaseURL retrieves the whole file.
            return Plan(listOf(Resource(base)))
        }
        val containers = path.mapNotNull { it.children(address.name()).firstOrNull() }
        val attributes = LinkedHashMap<String, String>()
        containers.forEach { element ->
            for (i in 0 until element.attributes.length) {
                val attr = element.attributes.item(i); attributes[attr.nodeName] = attr.nodeValue
            }
        }
        val resources = ArrayList<Resource>()
        fun add(relative: String, range: String = "") {
            if (resources.size >= MAX_RESOURCES) fail("resource_limit")
            resources += Resource(safe(base, relative), range.takeIf { it.isNotEmpty() }?.let(::range))
        }
        if (address.name() == "SegmentList") {
            val init = containers.asReversed().firstNotNullOfOrNull { it.children("Initialization").firstOrNull() }
            if (init != null) add(init.getAttribute("sourceURL"), init.getAttribute("range"))
            val segments = containers.asReversed().firstOrNull { it.children("SegmentURL").isNotEmpty() }
                ?.children("SegmentURL").orEmpty()
            if (segments.isEmpty()) fail("empty_segment_list")
            segments.forEach { add(it.getAttribute("media"), it.getAttribute("mediaRange")) }
        } else {
            val template = attributes["media"].orEmpty().takeIf { it.isNotEmpty() } ?: fail("missing_template")
            val representation = selected.second.getAttribute("id")
            val bandwidth = selected.second.getAttribute("bandwidth")
            val numberStart = integer(attributes["startNumber"] ?: "1", positive = false)
            val scale = integer(attributes["timescale"] ?: "1")
            if (scale > 1_000_000_000L) fail("timescale_limit")
            val offset = integer(attributes["presentationTimeOffset"] ?: "0", positive = false)
            val duration = seconds(period.getAttribute("duration")) ?: seconds(root.getAttribute("mediaPresentationDuration"))?.let {
                it - (seconds(period.getAttribute("start")) ?: 0.0)
            }
            val end = duration?.let {
                val ticks = it * scale
                if (ticks <= 0 || ticks > Long.MAX_VALUE.toDouble() / 2 || !ticks.isFinite()) fail("duration_limit")
                sum(offset, ceil(ticks).toLong())
            }
            attributes["initialization"]?.takeIf { it.isNotEmpty() }?.let {
                add(expand(it, representation, bandwidth, numberStart, offset))
            }
            var number = numberStart
            fun segment(time: Long) {
                add(expand(template, representation, bandwidth, number, time)); number = sum(number, 1)
            }
            val timeline = containers.asReversed().firstNotNullOfOrNull { it.children("SegmentTimeline").firstOrNull() }
            if (timeline != null) {
                val entries = timeline.children("S")
                if (entries.isEmpty()) fail("empty_timeline")
                var time = 0L
                entries.forEachIndexed { index, entry ->
                    if (entry.hasAttribute("t")) {
                        val nextTime = integer(entry.getAttribute("t"), positive = false)
                        if (nextTime < time) fail("timeline_order")
                        time = nextTime
                    }
                    val step = integer(entry.getAttribute("d"))
                    val repeats = entry.getAttribute("r").ifEmpty { "0" }.toLongOrNull() ?: fail("invalid_repeat")
                    if (repeats < -1) fail("invalid_repeat")
                    val count = if (repeats == -1L) {
                        val next = entries.getOrNull(index + 1)?.getAttribute("t")?.takeIf { it.isNotEmpty() }
                            ?.let { integer(it, positive = false) } ?: end ?: fail("unbounded_timeline")
                        if (next <= time) fail("timeline_order")
                        (next - time - 1) / step + 1
                    } else sum(repeats, 1)
                    if (count > MAX_RESOURCES - resources.size) fail("resource_limit")
                    repeat(count.toInt()) { segment(time); time = sum(time, step) }
                }
            } else {
                if (offset != 0L) fail("duration_offset_without_timeline")
                val step = integer(attributes["duration"] ?: fail("missing_segment_duration"))
                val stop = end ?: fail("missing_duration")
                val count = (stop - offset - 1) / step + 1
                if (count > MAX_RESOURCES - resources.size) fail("resource_limit")
                var time = offset
                repeat(count.toInt()) { segment(time); time = sum(time, step) }
            }
        }
        if (resources.isEmpty()) fail("empty_plan")
        return Plan(resources)
    }

    private fun expand(template: String, id: String, bandwidth: String, number: Long, time: Long): String {
        if (template.length > SafeUrls.MAX_LENGTH) fail("url_limit")
        val token = Regex("\\$\\$|\\$(RepresentationID|Bandwidth|Number|Time)(%0([1-9][0-9]?)d)?\\$")
        val output = StringBuilder()
        var cursor = 0
        token.findAll(template).forEach { match ->
            val literal = template.substring(cursor, match.range.first)
            if ('$' in literal) fail("template_token")
            output.append(literal)
            if (match.value == "$$") output.append('$') else {
                val kind = match.groupValues[1]
                val raw = when (kind) {
                    "RepresentationID" -> id
                    "Bandwidth" -> bandwidth
                    "Number" -> number.toString()
                    else -> time.toString()
                }
                if (raw.isEmpty()) fail("template_value")
                if (kind == "RepresentationID" && match.groupValues[2].isNotEmpty()) fail("template_format")
                val width = match.groupValues[3].toIntOrNull() ?: 0
                if (width > 20) fail("template_format")
                output.append(raw.padStart(width, '0'))
            }
            cursor = match.range.last + 1
        }
        val tail = template.substring(cursor)
        if ('$' in tail) fail("template_token")
        return output.append(tail).toString()
    }
    private fun range(value: String): LongRange {
        val pieces = value.split('-')
        if (pieces.size != 2) fail("invalid_range")
        val first = integer(pieces[0], positive = false)
        val last = integer(pieces[1], positive = false)
        if (last < first || last == Long.MAX_VALUE) fail("invalid_range")
        return first..last
    }
    private fun integer(value: String, positive: Boolean = true): Long = value.toLongOrNull()
        ?.takeIf { if (positive) it > 0 else it >= 0 } ?: fail("invalid_integer")
    private fun sum(a: Long, b: Long): Long = if (a >= 0 && b >= 0 && a <= Long.MAX_VALUE - b) a + b else fail("integer_overflow")
    private fun seconds(value: String): Double? {
        if (value.isEmpty()) return null
        val duration = runCatching { Duration.parse(value).toMillis() / 1000.0 }.getOrNull() ?: fail("invalid_duration")
        if (duration < 0 || duration > 86400) fail("duration_limit")
        return duration
    }
    private fun safe(base: String, relative: String): String {
        val url = runCatching { if (relative.isEmpty()) URI(base).toString() else URI(base).resolve(relative).toString() }.getOrDefault("")
        if (SafeUrls.audio(url).isEmpty() || URI(url).rawUserInfo != null || URI(url).rawFragment != null) fail("untrusted_url")
        return url
    }
    private fun Element.name() = localName ?: tagName.substringAfter(':')
    private fun Element.children(name: String? = null): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }.filter { name == null || it.name() == name }
    private fun Element.attrOr(parent: Element, name: String) = getAttribute(name).ifEmpty { parent.getAttribute(name) }
    private fun fail(reason: String): Nothing = throw Failure(reason)
}
