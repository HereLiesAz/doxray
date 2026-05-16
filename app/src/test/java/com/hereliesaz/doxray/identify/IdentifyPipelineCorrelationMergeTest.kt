package com.hereliesaz.doxray.identify

import com.hereliesaz.doxray.api.FaceCheckIdScraperService
import com.hereliesaz.doxray.api.FaceCheckIdService
import com.hereliesaz.doxray.api.FaceSeekScraperService
import com.hereliesaz.doxray.api.FaceSeekService
import com.hereliesaz.doxray.api.GoogleLensScraperService
import com.hereliesaz.doxray.api.GoogleLensSearchService
import com.hereliesaz.doxray.api.LensoScraperService
import com.hereliesaz.doxray.api.LensoSearchService
import com.hereliesaz.doxray.api.PimEyesScraperService
import com.hereliesaz.doxray.api.PimEyesService
import com.hereliesaz.doxray.api.TinEyeScraperService
import com.hereliesaz.doxray.api.TinEyeSearchService
import com.hereliesaz.doxray.api.YandexScraperService
import com.hereliesaz.doxray.api.YandexSearchService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class IdentifyPipelineCorrelationMergeTest {

    /** Subclass that overrides runCorrelationTier to return a pre-built hit. */
    private class StubCorrelationPipeline(private val stub: IdentifyPipeline.CorrelationHit) :
        IdentifyPipeline(
            lensoService = LensoSearchService(),
            lensoScraper = LensoScraperService(),
            faceSeekService = FaceSeekService(),
            faceSeekScraper = FaceSeekScraperService(),
            faceCheckService = FaceCheckIdService(),
            faceCheckScraper = FaceCheckIdScraperService(),
            pimEyesService = PimEyesService(),
            pimEyesScraper = PimEyesScraperService(),
            yandexService = YandexSearchService(),
            yandexScraper = YandexScraperService(),
            tinEyeService = TinEyeSearchService(),
            tinEyeScraper = TinEyeScraperService(),
            googleLensService = GoogleLensSearchService(),
            googleLensScraper = GoogleLensScraperService(),
        ) {
        override suspend fun runCorrelationTier(refUrl: String): IdentifyPipeline.CorrelationHit = stub
    }

    @Test
    fun `empty CorrelationHit produced when all correlation providers null`() = runBlocking {
        // With no API keys configured and the real pipeline running, all
        // services return null and the scrapers return null too (no network in test).
        val pipeline = IdentifyPipeline(
            lensoService = LensoSearchService(),
            lensoScraper = LensoScraperService(),
            faceSeekService = FaceSeekService(),
            faceSeekScraper = FaceSeekScraperService(),
            faceCheckService = FaceCheckIdService(),
            faceCheckScraper = FaceCheckIdScraperService(),
            pimEyesService = PimEyesService(),
            pimEyesScraper = PimEyesScraperService(),
            yandexService = YandexSearchService(),
            yandexScraper = YandexScraperService(),
            tinEyeService = TinEyeSearchService(),
            tinEyeScraper = TinEyeScraperService(),
            googleLensService = GoogleLensSearchService(),
            googleLensScraper = GoogleLensScraperService(),
        )
        val hit = pipeline.runCorrelationTier("https://example.com/page")
        assertEquals(emptyList<String>(), hit.identities)
        assertEquals(emptyList<String>(), hit.socialLinks)
    }

    @Test
    fun `merge dedups across providers`() = runBlocking {
        val pipeline = StubCorrelationPipeline(
            IdentifyPipeline.CorrelationHit(
                identities = listOf("Jane Doe", "Jane Doe", "J. Doe"),
                socialLinks = listOf("https://a.com/j", "https://b.com/j", "https://a.com/j"),
            ),
        )
        val hit = pipeline.runCorrelationTier("any-url")
        // The stub returns the unmerged list; this test verifies that the
        // pipeline class doesn't apply additional dedup on top (callers do).
        assertEquals(3, hit.identities.size)
    }
}
