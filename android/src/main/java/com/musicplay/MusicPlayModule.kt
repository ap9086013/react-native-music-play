package com.musicplay

import android.content.ComponentName
import android.net.Uri
import android.os.Handler
import android.os.Looper

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors


class MusicPlayModule(reactContext: ReactApplicationContext) :
  NativeMusicPlaySpec(reactContext) {
     companion object {
    const val NAME = "MusicPlay"
  }
   override fun getName(): String = NAME

  override fun addListener(eventName: String) {}
  override fun removeListeners(count: Double) {}

  private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pendingActions = mutableListOf<(MediaController) -> Unit>()

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private var currentUrl: String? = null
    private var currentMediaId: String? = null

    // [CHANGE 1] ------------------------------------------------
    // id -> last position in ms, so a podcast resumes where
    // the user left it when they swipe back to it.
    private val savedPositions = mutableMapOf<String, Long>()

    private fun rememberPosition(
        mediaId: String?,
        positionMs: Long,
        finished: Boolean = false
    ) {
        if (mediaId.isNullOrEmpty()) return
        if (finished) {
            savedPositions.remove(mediaId)
        } else if (positionMs > 1000L) {
            savedPositions[mediaId] = positionMs
        }
    }
    // -----------------------------------------------------------


    // --------------------------------------------------
    // CONTROLLER CONNECTION
    // --------------------------------------------------

    private fun ensureController(onReady: (MediaController) -> Unit) {
        controller?.let {
            onReady(it)
            return
        }

        pendingActions.add(onReady)

        if (controllerFuture != null) return

        val token = SessionToken(
            reactApplicationContext,
            ComponentName(reactApplicationContext, AudioPlaybackService::class.java)
        )

        val future = MediaController.Builder(reactApplicationContext, token).buildAsync()
        controllerFuture = future

        future.addListener({
            try {
                val readyController = future.get()
                controller = readyController
                attachListener(readyController)

                val actions = pendingActions.toList()
                pendingActions.clear()
                actions.forEach { it(readyController) }

            } catch (e: Exception) {
                pendingActions.clear()
                sendEvent("onError", Arguments.createMap().apply {
                    putString("message", e.message ?: "Unable to connect to audio service")
                })
            }
        }, MoreExecutors.directExecutor())
    }

    private fun attachListener(mc: MediaController) {
        mc.addListener(object : Player.Listener {

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {

                    Player.STATE_BUFFERING -> {
                        sendEvent("onLoading", Arguments.createMap().apply {
                            putBoolean("loading", true)
                            putString("url", currentUrl)
                            putString("id", currentMediaId)
                        })
                    }

                    Player.STATE_READY -> {
                        val durationMs = mc.duration
                        val durationSeconds =
                            if (durationMs > 0L) durationMs / 1000.0 else 0.0

                        sendEvent("onLoad", Arguments.createMap().apply {
                            putDouble("duration", durationSeconds)
                            putString("url", currentUrl)
                            putString("id", currentMediaId)
                        })

                        sendEvent("onLoading", Arguments.createMap().apply {
                            putBoolean("loading", false)
                            putString("url", currentUrl)
                            putString("id", currentMediaId)
                        })

                        sendEvent("onStateChange", Arguments.createMap().apply {
                            putString(
                                "state",
                                if (mc.isPlaying) "playing" else "paused"
                            )
                            putString("url", currentUrl)
                            putString("id", currentMediaId)
                        })
                    }

                    Player.STATE_ENDED -> {
                        // [CHANGE 2] A finished podcast restarts from 0 next time
                        rememberPosition(currentMediaId, 0L, finished = true)

                        sendEvent("onFinish", Arguments.createMap().apply {
                            putString("url", currentUrl)
                            putString("id", currentMediaId)
                        })

                        sendEvent("onStateChange", Arguments.createMap().apply {
                            putString("state", "stopped")
                            putString("url", currentUrl)
                            putString("id", currentMediaId)
                        })

                        stopProgressUpdates()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                sendEvent("onStateChange", Arguments.createMap().apply {
                    putString(
                        "state",
                        if (isPlaying) "playing" else "paused"
                    )
                    putString("url", currentUrl)
                    putString("id", currentMediaId)
                })
            }

            // [CHANGE 3] ------------------------------------------
            // Fires when the track changes (auto-advance, notification
            // next/previous, or a new playlist). Saves where the OLD
            // track was, so it can be resumed later.
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val oldId = oldPosition.mediaItem?.mediaId
                val newId = newPosition.mediaItem?.mediaId
                if (oldId == newId) return

                rememberPosition(
                    oldId,
                    oldPosition.positionMs,
                    finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                )
            }
            // -----------------------------------------------------

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                val uri = mediaItem?.localConfiguration?.uri?.toString()
                val mediaId = mediaItem?.mediaId

                if (uri != null) currentUrl = uri
                if (!mediaId.isNullOrEmpty()) currentMediaId = mediaId

                sendEvent("onTrackChanged", Arguments.createMap().apply {
                    putString("id", currentMediaId)
                    putString("url", currentUrl)
                    putString(
                        "reason",
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) "auto" else "seek"
                    )
                })
            }

            override fun onPlayerError(error: PlaybackException) {
                sendEvent("onError", Arguments.createMap().apply {
                    putString(
                        "message",
                        error.message ?: "Unknown audio error"
                    )
                    putString("url", currentUrl)
                    putString("id", currentMediaId)
                })
            }
        })
    }

    // --------------------------------------------------
    // PLAYLIST
    // --------------------------------------------------

    private fun mediaItemsFromReadableArray(items: ReadableArray): List<MediaItem> {
        val list = mutableListOf<MediaItem>()

        for (i in 0 until items.size()) {
            val map = items.getMap(i) ?: continue
            val id = map.getString("id") ?: continue
            val url = map.getString("url") ?: continue

            if (url.isBlank()) continue

            val title = if (map.hasKey("title")) map.getString("title") else null
            val artist = if (map.hasKey("artist")) map.getString("artist") else null
            val artworkUrl = if (map.hasKey("artworkUrl")) map.getString("artworkUrl") else null

            val metadataBuilder = MediaMetadata.Builder()

            if (!title.isNullOrBlank()) metadataBuilder.setTitle(title)
            if (!artist.isNullOrBlank()) metadataBuilder.setArtist(artist)
            if (!artworkUrl.isNullOrBlank()) {
                try {
                    metadataBuilder.setArtworkUri(Uri.parse(artworkUrl))
                } catch (_: Exception) {
                    // bad artwork URL, skip artwork
                }
            }

            list.add(
                MediaItem.Builder()
                    .setMediaId(id)
                    .setUri(url)
                    .setMediaMetadata(metadataBuilder.build())
                    .build()
            )
        }

        return list
    }

   override  fun playInPlaylist(items: ReadableArray, startid: String) {
        handler.post {
            val mediaItems = mediaItemsFromReadableArray(items)
            val startIndex = mediaItems.indexOfFirst { it.mediaId == startid }

            if (mediaItems.isEmpty() || startIndex == -1) {
                sendEvent("onError", Arguments.createMap().apply {
                    putString("message", "Track not found in playlist")
                    putString("id", startid)
                })
                return@post
            }

            currentMediaId = startid
            currentUrl = mediaItems[startIndex].localConfiguration?.uri?.toString()

            ensureController { mc ->
                try {
                    // [CHANGE 4a] Save where the previous track was
                    // before the playlist is replaced
                    rememberPosition(
                        mc.currentMediaItem?.mediaId,
                        mc.currentPosition,
                        mc.playbackState == Player.STATE_ENDED
                    )

                    val alreadyThisTrack =
                        mc.currentMediaItem?.mediaId == startid &&
                            (mc.playbackState == Player.STATE_READY ||
                                mc.playbackState == Player.STATE_BUFFERING ||
                                mc.playbackState == Player.STATE_ENDED)

                    if (alreadyThisTrack) {
                        if (mc.playbackState == Player.STATE_ENDED) {
                            mc.seekTo(startIndex, 0L)
                        }
                        sendEvent("onLoading", Arguments.createMap().apply {
                            putBoolean("loading", false)
                            putString("url", currentUrl)
                            putString("id", currentMediaId)
                        })
                        mc.play()
                        startProgressUpdates()
                        return@ensureController
                    }

                    sendEvent("onLoading", Arguments.createMap().apply {
                        putBoolean("loading", true)
                        putString("url", currentUrl)
                        putString("id", currentMediaId)
                    })

                    mc.repeatMode = Player.REPEAT_MODE_OFF

                    // [CHANGE 4b] Start from the saved position (was: 0L)
                    val resumeMs = savedPositions[startid] ?: 0L
                    mc.setMediaItems(mediaItems, startIndex, resumeMs)

                    mc.prepare()
                    mc.play()
                    startProgressUpdates()
                } catch (e: Exception) {
                    sendEvent("onError", Arguments.createMap().apply {
                        putString("message", e.message ?: "Unable to play audio")
                        putString("url", currentUrl)
                        putString("id", currentMediaId)
                    })
                }
            }
        }
    }

    
   override fun getCurrentTrackInfo(promise: Promise) {
        handler.post {
            ensureController { mc ->
                promise.resolve(
                    Arguments.createMap().apply {
                        putString("id", currentMediaId)
                        putString("url", currentUrl)
                        putDouble("positionSeconds", mc.currentPosition / 1000.0)
                        putBoolean("isPlaying", mc.isPlaying)
                    }
                )
            }
        }
    }

    // --------------------------------------------------
    // LEGACY SINGLE-TRACK PLAY
    // --------------------------------------------------

    override fun play(url: String) {
        handler.post {
            if (url.isBlank()) {
                sendEvent("onError", Arguments.createMap().apply {
                    putString("message", "Audio URL is empty")
                })
                return@post
            }

            currentUrl = url
            currentMediaId = null

            sendEvent("onLoading", Arguments.createMap().apply {
                putBoolean("loading", true)
                putString("url", currentUrl)
            })

            ensureController { mc ->
                try {
                    mc.repeatMode = Player.REPEAT_MODE_OFF
                    mc.setMediaItem(MediaItem.fromUri(url))
                    mc.prepare()
                    mc.play()
                    startProgressUpdates()
                } catch (e: Exception) {
                    sendEvent("onError", Arguments.createMap().apply {
                        putString("message", e.message ?: "Unable to play audio")
                        putString("url", currentUrl)
                    })
                }
            }
        }
    }

    // --------------------------------------------------
    // PAUSE / RESUME / STOP / SEEK
    // --------------------------------------------------

    
    override fun pause(url: String?) {
        handler.post {
            if (url != null && url != currentUrl) return@post
            ensureController { it.pause() }
        }
    }

    
    override fun resume(url: String?) {
        handler.post {
            if (url != null && url != currentUrl) return@post
            ensureController { it.play() }
        }
    }

    
    override fun stop(url: String?) {
        handler.post {
            if (url != null && url != currentUrl) {
                return@post
            }

            val stoppedUrl = currentUrl
            val stoppedid = currentMediaId

            ensureController { mc ->
                // [CHANGE 5] Save position before stop() clears the playlist
                rememberPosition(
                    mc.currentMediaItem?.mediaId,
                    mc.currentPosition,
                    mc.playbackState == Player.STATE_ENDED
                )

                mc.stop()
                mc.clearMediaItems()

                sendEvent(
                    "onStateChange",
                    Arguments.createMap().apply {
                        putString("state", "stopped")
                        putString("url", stoppedUrl)
                        putString("id", stoppedid)
                    }
                )
            }

            stopProgressUpdates()
            currentUrl = null
            currentMediaId = null
        }
    }

    
    override fun seekTo(seconds: Double, url: String?) {
        handler.post {
            if (url != null && url != currentUrl) return@post
            val ms = (seconds * 1000.0).toLong()
            ensureController { it.seekTo(ms) }
        }
    }

    
    override fun getDuration(promise: Promise) {
        handler.post {
            ensureController { mc ->
                val durationMs = mc.duration
                promise.resolve(if (durationMs > 0L) durationMs / 1000.0 else 0.0)
            }
        }
    }

    
    override fun getCurrentTime(promise: Promise) {
        handler.post {
            ensureController { mc -> promise.resolve(mc.currentPosition / 1000.0) }
        }
    }

    
    override fun setLooping(enabled: Boolean) {
        handler.post {
            ensureController { mc ->
                mc.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
        }
    }

    // --------------------------------------------------
    // PROGRESS
    // --------------------------------------------------

    private fun startProgressUpdates() {
        stopProgressUpdates()

        progressRunnable = object : Runnable {
            override fun run() {
                controller?.let { mc ->
                    val positionMs = mc.currentPosition.coerceAtLeast(0L)
                    val durationMs = mc.duration

                    sendEvent("onProgress", Arguments.createMap().apply {
                        putDouble("currentTime", positionMs / 1000.0)
                        putDouble("duration", if (durationMs > 0L) durationMs / 1000.0 else 0.0)
                        putString("url", currentUrl)
                        putString("id", currentMediaId)
                    })
                }
                handler.postDelayed(this, 500L)
            }
        }

        handler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun sendEvent(eventName: String, params: WritableMap?) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    // --------------------------------------------------
    // CLEANUP
    // --------------------------------------------------

    override fun invalidate() {
        handler.post {
            stopProgressUpdates()
            controller?.release()
            controller = null
            controllerFuture = null
        }
        super.invalidate()
    }
}