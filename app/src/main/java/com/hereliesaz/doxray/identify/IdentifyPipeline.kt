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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Owns the identify pipeline orchestration. Two methods:
 *   - runFaceTier: parallel fan-out across the 4 face providers; dedup by URL.
 *   - runCorrelationTier: parallel fan-out across the 3 correlation providers
 *     for a single reference URL; merge identities + socialLinks.
 *
 * Each provider is tried API first, then scraper fallback, inside its own
 * coroutine. The pipeline awaits all and applies the merge / dedup rules.
 */
open class IdentifyPipeline(
    private val lensoService: LensoSearchService,
    private val lensoScraper: LensoScraperService,
    private val faceSeekService: FaceSeekService,
    private val faceSeekScraper: FaceSeekScraperService,
    private val faceCheckService: FaceCheckIdService,
    private val faceCheckScraper: FaceCheckIdScraperService,
    private val pimEyesService: PimEyesService,
    private val pimEyesScraper: PimEyesScraperService,
    private val yandexService: YandexSearchService,
    private val yandexScraper: YandexScraperService,
    private val tinEyeService: TinEyeSearchService,
    private val tinEyeScraper: TinEyeScraperService,
    private val googleLensService: GoogleLensSearchService,
    private val googleLensScraper: GoogleLensScraperService,
) {

    data class FaceHit(
        val provider: String,
        val faceId: String,
        val confidence: Float,
        val referenceUrl: String,
    )

    data class CorrelationHit(
        val identities: List<String>,
        val socialLinks: List<String>,
    )

    open suspend fun runFaceTier(imageBytes: ByteArray): List<FaceHit> = coroutineScope {
        val raw = listOf(
            async { lensoFaceHit(imageBytes) },
            async { faceSeekFaceHit(imageBytes) },
            async { faceCheckFaceHit(imageBytes) },
            async { pimEyesFaceHit(imageBytes) },
        ).awaitAll()
        applyFaceTierRules(raw)
    }

    open suspend fun runCorrelationTier(refUrl: String): CorrelationHit = coroutineScope {
        val results = listOf(
            async { yandexService.searchIdentity(refUrl) ?: yandexScraper.searchIdentity(refUrl) },
            async { tinEyeService.searchIdentity(refUrl) ?: tinEyeScraper.searchIdentity(refUrl) },
            async { googleLensService.searchIdentity(refUrl) ?: googleLensScraper.searchIdentity(refUrl) },
        ).awaitAll()
        val identities = mutableListOf<String>()
        val links = mutableListOf<String>()
        for (r in results) when (r) {
            is YandexSearchService.Result -> { identities += r.identities; links += r.socialLinks }
            is TinEyeSearchService.Result -> { identities += r.identities; links += r.socialLinks }
            is GoogleLensSearchService.Result -> { identities += r.identities; links += r.socialLinks }
            else -> { /* null — skip */ }
        }
        CorrelationHit(
            identities = identities.distinct(),
            socialLinks = links.distinct(),
        )
    }

    private suspend fun lensoFaceHit(bytes: ByteArray): FaceHit? {
        val r = lensoService.identifyFace(bytes) ?: lensoScraper.identifyFace(bytes) ?: return null
        return FaceHit("lenso", r.faceId, r.confidence, r.referenceImageUrl)
    }

    private suspend fun faceSeekFaceHit(bytes: ByteArray): FaceHit? {
        val r = faceSeekService.identifyFace(bytes) ?: faceSeekScraper.identifyFace(bytes) ?: return null
        return FaceHit("faceseek", r.faceId, r.confidence, r.referenceImageUrl)
    }

    private suspend fun faceCheckFaceHit(bytes: ByteArray): FaceHit? {
        val r = faceCheckService.identifyFace(bytes) ?: faceCheckScraper.identifyFace(bytes) ?: return null
        return FaceHit("facecheck", r.faceId, r.confidence, r.referenceImageUrl)
    }

    private suspend fun pimEyesFaceHit(bytes: ByteArray): FaceHit? {
        val r = pimEyesService.identifyFace(bytes) ?: pimEyesScraper.identifyFace(bytes) ?: return null
        return FaceHit("pimeyes", r.faceId, r.confidence, r.referenceImageUrl)
    }

    companion object {
        const val FACE_CONFIDENCE_THRESHOLD = 0.6f

        /**
         * Pure-logic rules applied to raw face-tier hits: drop nulls,
         * filter by confidence threshold, dedup by reference URL. Exposed
         * as a separate function so tests can exercise the rules directly
         * with curated input — and so the production runFaceTier and tests
         * share the same logic.
         */
        fun applyFaceTierRules(raw: List<FaceHit?>): List<FaceHit> =
            raw.filterNotNull()
                .filter { it.confidence > FACE_CONFIDENCE_THRESHOLD }
                .distinctBy { it.referenceUrl }
    }
}
