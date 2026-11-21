package com.example.vkweb.media

import org.junit.Assert.*
import org.junit.Test

class DashAudioPlanTest {
    private val base = "https://vd1.okcdn.ru/music/manifest?signature=keep"
    private fun mpd(body: String, duration: String = "PT6S", type: String = "static") =
        """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="$type" mediaPresentationDuration="$duration"><Period>$body</Period></MPD>""".replace('@', '$')
    private fun audio(body: String) = """<AdaptationSet mimeType="audio/mp4"><Representation id="audio" bandwidth="128000">$body</Representation></AdaptationSet>"""
    private fun plan(xml: String) = DashAudioPlan.parse(xml, base)
    private fun failure(reason: String, xml: String) {
        try { plan(xml); fail("Expected $reason") } catch (error: DashAudioPlan.Failure) { assertEquals(reason, error.reason) }
    }

    @Test fun segmentBaseDownloadsCompleteFileInsteadOfOneObservedRange() {
        val result = plan(mpd(audio("""<BaseURL>track.mp4?key=abc&amp;x=2</BaseURL><SegmentBase indexRange="20-40"><Initialization range="0-19"/></SegmentBase>""")))
        assertEquals(listOf(DashAudioPlan.Resource("https://vd1.okcdn.ru/music/track.mp4?key=abc&x=2")), result.resources)
    }
    @Test fun picksAudioRepresentationInsteadOfHigherBandwidthVideo() {
        val xml = mpd("""<AdaptationSet mimeType="video/mp4"><Representation bandwidth="999999"><BaseURL>video.mp4</BaseURL></Representation></AdaptationSet>""" + audio("<BaseURL>audio.mp4</BaseURL>"))
        assertTrue(plan(xml).resources.single().url.endsWith("audio.mp4"))
    }
    @Test fun highestUnencryptedAudioRepresentationIsSelected() {
        val xml = mpd("""<AdaptationSet contentType="audio"><Representation bandwidth="256000"><ContentProtection/><BaseURL>locked.mp4</BaseURL></Representation><Representation bandwidth="128000"><BaseURL>clear.mp4</BaseURL></Representation><Representation bandwidth="64000"><BaseURL>low.mp4</BaseURL></Representation></AdaptationSet>""")
        assertTrue(plan(xml).resources.single().url.endsWith("clear.mp4"))
    }
    @Test fun inheritedBaseUrlsAndSegmentListRangesAreResolved() {
        val xml = mpd("""<BaseURL>sub/</BaseURL>""" + audio("""<BaseURL>file.mp4?token=keep</BaseURL><SegmentList><Initialization range="0-9"/><SegmentURL mediaRange="10-19"/><SegmentURL media="other.m4s"/></SegmentList>"""))
        val result = plan(xml).resources
        assertEquals("https://vd1.okcdn.ru/music/sub/file.mp4?token=keep", result[0].url)
        assertEquals(result[0].url, result[1].url); assertEquals(0L..9L, result[0].range); assertEquals(10L..19L, result[1].range)
        assertEquals("https://vd1.okcdn.ru/music/sub/other.m4s", result[2].url)
    }
    @Test fun templateNumberFormattingAndInheritedAttributes() {
        val xml = mpd("""<AdaptationSet contentType="audio"><SegmentTemplate timescale="10" duration="20" initialization="init-@RepresentationID@.mp4" media="chunk-@Number%05d@-@Bandwidth@.m4s" startNumber="7"/><Representation id="a" bandwidth="96000"/></AdaptationSet>""")
        assertEquals(listOf("init-a.mp4", "chunk-00007-96000.m4s", "chunk-00008-96000.m4s", "chunk-00009-96000.m4s"), plan(xml).resources.map { it.url.substringAfterLast('/') })
    }
    @Test fun finiteTimelineUsesTimeTokensAndRepeatedSegments() {
        val xml = mpd(audio("""<SegmentTemplate media="@Time@.m4s"><SegmentTimeline><S t="30" d="10" r="2"/><S d="20"/></SegmentTimeline></SegmentTemplate>"""))
        assertEquals(listOf("30.m4s", "40.m4s", "50.m4s", "60.m4s"), plan(xml).resources.map { it.url.substringAfterLast('/') })
    }
    @Test fun negativeRepeatStopsAtNextExplicitTime() {
        val xml = mpd(audio("""<SegmentTemplate media="@Time@.m4s"><SegmentTimeline><S t="0" d="2" r="-1"/><S t="4" d="2"/></SegmentTimeline></SegmentTemplate>"""))
        assertEquals(listOf("0.m4s", "2.m4s", "4.m4s"), plan(xml).resources.map { it.url.substringAfterLast('/') })
    }
    @Test fun negativeRepeatAtEndUsesPresentationDurationAndOffset() {
        val xml = mpd(audio("""<SegmentTemplate media="@Time@.m4s" timescale="10" presentationTimeOffset="100"><SegmentTimeline><S t="100" d="20" r="-1"/></SegmentTimeline></SegmentTemplate>"""))
        assertEquals(listOf("100.m4s", "120.m4s", "140.m4s"), plan(xml).resources.map { it.url.substringAfterLast('/') })
    }
    @Test fun escapedDollarInTemplateIsPreserved() {
        val xml = mpd(audio("""<SegmentTemplate media="@@-@Number@.m4s" duration="6"/>"""))
        assertTrue(plan(xml).resources.single().url.endsWith("$-1.m4s"))
    }
    @Test fun rejectsLiveManifests() = failure("dynamic_manifest", mpd(audio("<BaseURL>a.mp4</BaseURL>"), type = "dynamic"))
    @Test fun rejectsMultiplePeriodsRatherThanSavingOnlyPartOfTrack() = failure("period_count", "<MPD><Period/><Period/></MPD>")
    @Test fun rejectsProtectedAudio() = failure("protected_audio", mpd(audio("<ContentProtection/><BaseURL>locked.mp4</BaseURL>")))
    @Test fun rejectsManifestsWithoutAudio() = failure("no_audio", mpd("<AdaptationSet mimeType='video/mp4'><Representation><BaseURL>video.mp4</BaseURL></Representation></AdaptationSet>"))
    @Test fun rejectsExternalAndInsecureUrls() {
        failure("untrusted_url", mpd(audio("<BaseURL>https://evil.example/a.mp4</BaseURL>")))
        failure("untrusted_url", mpd(audio("<BaseURL>http://vd1.okcdn.ru/a.mp4</BaseURL>")))
        failure("untrusted_url", mpd(audio("<BaseURL>https://u:p&#64;vd1.okcdn.ru/a.mp4</BaseURL>")))
    }
    @Test fun rejectsEntityDeclarationsBeforeXmlParsing() {
        failure("xml_declaration", "<!DOCTYPE MPD [<!ENTITY file SYSTEM 'file:///secret'>]><MPD><Period/></MPD>")
    }
    @Test fun rejectsHugeManifestAndSegmentCount() {
        failure("manifest_limit", " ".repeat(DashAudioPlan.MAX_XML_CHARS + 1))
        failure("resource_limit", mpd(audio("<SegmentTemplate media='a.m4s'><SegmentTimeline><S d='1' r='10000'/></SegmentTimeline></SegmentTemplate>")))
    }
    @Test fun rejectsInvalidRangesAndUnknownTokens() {
        failure("invalid_range", mpd(audio("<BaseURL>a.mp4</BaseURL><SegmentList><SegmentURL mediaRange='9-0'/></SegmentList>")))
        failure("template_token", mpd(audio("<SegmentTemplate duration='6' media='@Unknown@.m4s'/>")))
    }
    @Test fun rejectsUnboundedTimeline() = failure("unbounded_timeline", mpd(audio("<SegmentTemplate media='a.m4s'><SegmentTimeline><S d='1' r='-1'/></SegmentTimeline></SegmentTemplate>"), duration = ""))
    @Test fun rejectsOverflowAndOverlappingTimeline() {
        failure("integer_overflow", mpd(audio("<SegmentTemplate media='a.m4s'><SegmentTimeline><S t='9223372036854775806' d='2'/></SegmentTimeline></SegmentTemplate>")))
        failure("timeline_order", mpd(audio("<SegmentTemplate media='a.m4s'><SegmentTimeline><S t='10' d='2'/><S t='1' d='2'/></SegmentTimeline></SegmentTemplate>")))
    }
    @Test fun manifestUrlCannotBeUsedAsAudioWithoutExplicitBaseOrSegments() = failure("missing_audio_url", mpd(audio("")))
    @Test fun namespacePrefixesAreSupported() {
        val xml = "<d:MPD xmlns:d='urn:mpeg:dash:schema:mpd:2011'><d:Period><d:AdaptationSet contentType='audio'><d:Representation><d:BaseURL>a.mp4</d:BaseURL></d:Representation></d:AdaptationSet></d:Period></d:MPD>"
        assertTrue(plan(xml).resources.single().url.endsWith("a.mp4"))
    }
    @Test fun offsetWithoutTimelineIsRejectedRatherThanGuessingTimeTokens() = failure("duration_offset_without_timeline", mpd(audio("<SegmentTemplate duration='2' presentationTimeOffset='1' media='@Time@.m4s'/>")))
}
