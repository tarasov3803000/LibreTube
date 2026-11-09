package com.github.libretube.helpers

import android.util.Log
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.SubscriptionHelper
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.db.DatabaseHelper
import com.github.libretube.enums.WatchHistoryStatus
import com.github.libretube.extensions.toID
import com.github.libretube.repo.UserDataRepositoryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Наша сборка: блок «Для Вас» на главной — рекомендации без аккаунта, на телефоне.
 *
 * Затравки — последние просмотренные видео (нет истории — свежее из подписок, нет и их — тренды).
 * К каждой затравке берутся «похожие» видео, которые YouTube отдаёт под роликом.
 * Видео, похожее на несколько недавних просмотров, поднимается выше; каналы из подписок — чуть выше.
 * Каждая четвёртая карточка — свежее видео из подписок за неделю.
 * Просмотренное, Shorts и трансляции (по фильтрам приложения) отсеиваются, с канала — не больше двух.
 */
object ForYouHelper {
    private const val TAG = "ForYou"
    private const val SEEDS = 8
    private const val MIN_SEEDS = 3
    private const val RESULT_SIZE = 24
    private const val MAX_PER_CHANNEL = 2
    private const val SUBSCRIPTION_EVERY = 4
    private const val FRESH_MS = 7L * 24 * 60 * 60 * 1000
    private const val SUBSCRIBED_BOOST = 1.3

    // похожие видео по затравке — на время работы приложения, чтобы обновление ленты было мгновенным
    private val relatedCache = ConcurrentHashMap<String, List<StreamItem>>()
    private val requests = Semaphore(3)

    private val StreamItem.videoId get() = url?.toID()

    suspend fun load(
        subscriptionFeed: List<StreamItem>?,
        trending: List<StreamItem>?
    ): List<StreamItem> = withContext(Dispatchers.IO) {
        val history = runCatching {
            UserDataRepositoryHelper.userDataRepository
                .getWatchHistory(100, null, WatchHistoryStatus.ALL).first
        }.getOrDefault(emptyList())
        val historyIds = history.map { it.metadata.videoId }.toSet()

        val freshSubscriptions = subscriptionFeed.orEmpty()
            .filter { System.currentTimeMillis() - it.uploaded < FRESH_MS }
            .sortedByDescending { it.uploaded }

        val seeds = history.map { it.metadata.videoId }.distinct().take(SEEDS).toMutableList()
        listOf(freshSubscriptions, trending.orEmpty()).forEach { fallback ->
            if (seeds.size < MIN_SEEDS) {
                seeds += fallback.mapNotNull { it.videoId }.filter { it !in seeds }.take(SEEDS - seeds.size)
            }
        }

        val relatedPerSeed = coroutineScope {
            seeds.map { seedId -> async { related(seedId) } }.awaitAll()
        }

        val scores = HashMap<String, Double>()
        val candidates = LinkedHashMap<String, StreamItem>()
        relatedPerSeed.forEachIndexed { seedIndex, related ->
            val seedWeight = 1.0 - seedIndex * 0.06
            related.forEachIndexed { rank, item ->
                val id = item.videoId ?: return@forEachIndexed
                candidates.putIfAbsent(id, item)
                scores[id] = scores.getOrDefault(id, 0.0) + seedWeight / (1 + rank * 0.15)
            }
        }

        val subscribed = runCatching {
            SubscriptionHelper.getSubscriptionChannelIds().toSet()
        }.getOrDefault(emptySet())
        val random = Random(System.currentTimeMillis())
        fun isNew(item: StreamItem) = item.videoId.let { it != null && it !in historyIds && it !in seeds }

        val ranked = DatabaseHelper.filterByStreamTypeAndWatchPosition(
            candidates.values.filter(::isNew), hideWatched = true, showUpcoming = false
        ).sortedByDescending { item ->
            val boost = if (item.uploaderUrl?.toID() in subscribed) SUBSCRIBED_BOOST else 1.0
            scores.getOrDefault(item.videoId, 0.0) * boost * (0.75 + random.nextDouble() * 0.5)
        }
        val fresh = DatabaseHelper.filterByStreamTypeAndWatchPosition(
            freshSubscriptions.filter(::isNew), hideWatched = true, showUpcoming = false
        ).take(RESULT_SIZE).shuffled(random)

        merge(ranked, fresh).also {
            Log.d(TAG, "seeds=${seeds.size} related=${relatedPerSeed.map { r -> r.size }} candidates=${candidates.size} ranked=${ranked.size} fresh=${fresh.size} result=${it.size}")
        }
    }

    private fun merge(ranked: List<StreamItem>, fresh: List<StreamItem>): List<StreamItem> {
        val result = mutableListOf<StreamItem>()
        val usedIds = HashSet<String>()
        val perChannel = HashMap<String, Int>()
        val rankedIt = ranked.iterator()
        val freshIt = fresh.iterator()

        fun tryAdd(item: StreamItem): Boolean {
            val id = item.videoId ?: return false
            val channel = item.uploaderUrl ?: item.uploaderName.orEmpty()
            if (id in usedIds || perChannel.getOrDefault(channel, 0) >= MAX_PER_CHANNEL) return false
            usedIds += id
            perChannel[channel] = perChannel.getOrDefault(channel, 0) + 1
            result += item
            return true
        }

        fun takeFrom(source: Iterator<StreamItem>): Boolean {
            while (source.hasNext()) if (tryAdd(source.next())) return true
            return false
        }

        while (result.size < RESULT_SIZE) {
            val wantFresh = (result.size + 1) % SUBSCRIPTION_EVERY == 0
            val added = if (wantFresh) takeFrom(freshIt) || takeFrom(rankedIt)
            else takeFrom(rankedIt) || takeFrom(freshIt)
            if (!added) break
        }
        return result
    }

    private suspend fun related(videoId: String): List<StreamItem> {
        relatedCache[videoId]?.let { return it }
        return requests.withPermit {
            runCatching {
                MediaServiceRepository.instance.getStreams(videoId).relatedStreams
                    .filter { it.url != null && (it.type == null || it.type == StreamItem.TYPE_STREAM) }
            }.onSuccess { relatedCache[videoId] = it }
                .onFailure { Log.w(TAG, "related($videoId) failed", it) }
                .getOrDefault(emptyList())
        }
    }
}
