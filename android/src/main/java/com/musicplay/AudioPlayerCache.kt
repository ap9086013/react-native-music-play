package com.musicplay

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@UnstableApi
object AudioPlayerCache {

  private const val CACHE_DIR = "musicplay_audio_cache"
  private const val MAX_CACHE_BYTES = 100L * 1024L * 1024L // 100 MB

  @Volatile
  private var cache: SimpleCache? = null

  private fun getCache(context: Context): SimpleCache {
    return cache ?: synchronized(this) {
      cache ?: SimpleCache(
        File(context.applicationContext.cacheDir, CACHE_DIR),
        LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
        StandaloneDatabaseProvider(context.applicationContext)
      ).also { cache = it }
    }
  }

  fun getDataSourceFactory(context: Context): DataSource.Factory {
    val appContext = context.applicationContext

    val httpFactory = DefaultHttpDataSource.Factory()
      .setAllowCrossProtocolRedirects(true)

    val upstreamFactory = DefaultDataSource.Factory(appContext, httpFactory)

    return CacheDataSource.Factory()
      .setCache(getCache(appContext))
      .setUpstreamDataSourceFactory(upstreamFactory)
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
  }
}