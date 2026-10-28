package com.snag.downloader.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Typed failures, mirroring the error taxonomy of the reference pipeline. */
sealed class ExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Timeout(val strategy: String) : ExtractionException("timed out in $strategy")

    class NoMedia(val strategy: String, val reason: String) :
        ExtractionException("no media in $strategy: $reason")

    class Failed(val strategy: String, cause: Throwable?) :
        ExtractionException("$strategy failed: ${cause?.message ?: cause?.javaClass?.simpleName}", cause)

    /** The site answered with a bot check or login wall instead of content. */
    class Blocked(val strategy: String, val reason: String) :
        ExtractionException("$strategy blocked: $reason")

    class Unsupported(val url: String) : ExtractionException("no extractor for $url")
}

/** One technique for one platform. Strategies are independent and interchangeable. */
interface Strategy {
    val id: String
    val timeoutMs: Long
    suspend fun extract(url: String): MediaBundle
}

interface Extractor {
    val platform: SourcePlatform
    fun strategies(): List<Strategy>
}

data class StrategyFailure(
    val strategy: String,
    val message: String,
    val elapsedMs: Long,
)

data class Resolution(
    val bundle: MediaBundle?,
    val failures: List<StrategyFailure>,
    val elapsedMs: Long,
) {
    val ok: Boolean get() = bundle != null
}

/**
 * Runs the strategy list for a platform and returns the first success plus every
 * failure that preceded it. No silent fallbacks, no hidden retries.
 */
class ExtractorRegistry(extractors: List<Extractor>) {

    private val byPlatform: Map<SourcePlatform, Extractor> =
        extractors.associateBy { it.platform }

    fun extractorFor(url: String): Extractor? = byPlatform[UrlParser.classify(url)]

    suspend fun resolve(url: String): Resolution {
        val started = System.currentTimeMillis()
        val extractor = extractorFor(url)
            ?: return Resolution(
                bundle = null,
                failures = listOf(
                    StrategyFailure("registry", "no extractor for ${UrlParser.hostOf(url)}", 0)
                ),
                elapsedMs = System.currentTimeMillis() - started,
            )

        val failures = mutableListOf<StrategyFailure>()
        for (strategy in extractor.strategies()) {
            val t0 = System.currentTimeMillis()
            try {
                val bundle = withTimeout(strategy.timeoutMs) { strategy.extract(url) }
                return Resolution(
                    bundle = bundle.copy(elapsedMs = System.currentTimeMillis() - t0),
                    failures = failures,
                    elapsedMs = System.currentTimeMillis() - started,
                )
            } catch (t: TimeoutCancellationException) {
                failures += StrategyFailure(strategy.id, "timeout after ${strategy.timeoutMs} ms", System.currentTimeMillis() - t0)
            } catch (e: ExtractionException) {
                failures += StrategyFailure(strategy.id, e.message ?: "failed", System.currentTimeMillis() - t0)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                failures += StrategyFailure(strategy.id, t.message ?: t.javaClass.simpleName, System.currentTimeMillis() - t0)
            }
        }

        return Resolution(null, failures, System.currentTimeMillis() - started)
    }
}
