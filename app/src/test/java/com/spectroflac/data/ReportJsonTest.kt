package com.spectroflac.data

import com.spectroflac.analysis.AnalysisReport
import com.spectroflac.analysis.StereoInfo
import com.spectroflac.analysis.Verdict
import com.spectroflac.flac.ContainerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReportJsonTest {
    private fun report() = AnalysisReport(
        fileName = "a.flac", uri = "content://a", container = ContainerKind.FLAC, verdict = Verdict.FAKE,
        confidence = 88, headline = "16 BIT", summary = "s", findings = emptyList(), technical = null, encoder = null,
        spectral = null, integrity = null, dynamics = null, tags = mapOf("TITLE" to "T"), coverBytes = null,
        spectrogram = null, analysedAtMillis = 1234, analysisDurationMillis = 56,
        stereo = StereoInfo(0.8, 0.5, -9.0, -20.0, -30.0, 0.1, null),
        sourceModifiedMillis = 1_700_000_000_000, analyzerVersion = 3,
    )

    @Test
    fun `round trip keeps the fields skip-known files depends on`() {
        val back = ReportJson.fromJson(ReportJson.toJson(report()))
        assertEquals(1_700_000_000_000, back.sourceModifiedMillis)
        assertEquals(3, back.analyzerVersion)
        assertEquals(Verdict.FAKE, back.verdict)
        assertEquals("T", back.title)
    }

    @Test
    fun `round trip keeps the stereo summary`() {
        val stereo = ReportJson.fromJson(ReportJson.toJson(report())).stereo
        assertNotNull(stereo)
        assertEquals(0.8, stereo!!.correlation, 1e-9)
        assertEquals(-9.0, stereo.widthDb, 1e-9)
    }

    @Test
    fun `an old record without the new fields still loads`() {
        val json = ReportJson.toJson(report()).apply { remove("sourceModified"); remove("analyzerVersion") }
        val back = ReportJson.fromJson(json)
        assertEquals(0L, back.sourceModifiedMillis)
        assertEquals(0, back.analyzerVersion)
    }
}
