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
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifyPipelineFaceTierTest {

    @Test
    fun `face tier returns empty when all providers return null`() = runBlocking {
        // Exercises real coroutine fan-out + applyFaceTierRules. With no
        // BuildConfig keys configured, every service .identifyFace returns
        // null; with no network access, every scraper .identifyFace also
        // returns null. The pipeline correctly produces an empty list.
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
        val hits = pipeline.runFaceTier(ByteArray(0))
        assertEquals(0, hits.size)
    }

    @Test
    fun `applyFaceTierRules picks confident hits and dedups by url`() {
        // Direct unit test of the rules — exercises production code, not a test override.
        val raw = listOf(
            IdentifyPipeline.FaceHit("lenso", "id-1", 0.9f, "https://example.com/a"),
            IdentifyPipeline.FaceHit("faceseek", "id-2", 0.7f, "https://example.com/a"),
            IdentifyPipeline.FaceHit("facecheck", "id-3", 0.65f, "https://example.com/b"),
            IdentifyPipeline.FaceHit("pimeyes", "id-4", 0.5f, "https://example.com/c"), // below threshold
            null, // a provider that returned null
        )
        val hits = IdentifyPipeline.applyFaceTierRules(raw)
        // 0.5 below threshold filtered; duplicate URL deduped; null skipped
        assertEquals(2, hits.size)
        assertTrue(hits.any { it.referenceUrl == "https://example.com/a" })
        assertTrue(hits.any { it.referenceUrl == "https://example.com/b" })
    }

    @Test
    fun `applyFaceTierRules yields none when no provider exceeds threshold`() {
        val raw = listOf(
            IdentifyPipeline.FaceHit("lenso", "id-1", 0.5f, "https://example.com/a"),
            IdentifyPipeline.FaceHit("faceseek", "id-2", 0.55f, "https://example.com/b"),
        )
        val hits = IdentifyPipeline.applyFaceTierRules(raw)
        assertEquals(0, hits.size)
    }
}
