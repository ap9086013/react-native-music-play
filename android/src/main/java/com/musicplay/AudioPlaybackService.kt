package com.musicplay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList

@UnstableApi
class AudioPlaybackService : MediaSessionService() {

  companion object {
    private const val ACTION_STOP_FROM_NOTIFICATION =
      "com.musicplay.ACTION_STOP_FROM_NOTIFICATION"
  }

  private var mediaSession: MediaSession? = null
  private var player: ExoPlayer? = null

  private val dismissReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == ACTION_STOP_FROM_NOTIFICATION) {
        stopPlaybackAndService()
      }
    }
  }

  private inner class DismissAwareNotificationProvider(
    context: Context
  ) : MediaNotification.Provider {

    private val delegate = DefaultMediaNotificationProvider(context)

    override fun createNotification(
      mediaSession: MediaSession,
      customLayout: ImmutableList<CommandButton>,
      actionFactory: MediaNotification.ActionFactory,
      onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
      val mediaNotification = delegate.createNotification(
        mediaSession, customLayout, actionFactory, onNotificationChangedCallback
      )

      val deleteIntent = PendingIntent.getBroadcast(
        this@AudioPlaybackService,
        0,
        Intent(ACTION_STOP_FROM_NOTIFICATION).setPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )

      mediaNotification.notification.deleteIntent = deleteIntent
      return mediaNotification
    }

    override fun handleCustomCommand(
      session: MediaSession,
      action: String,
      extras: Bundle
    ): Boolean = delegate.handleCustomCommand(session, action, extras)
  }

  override fun onCreate() {
    super.onCreate()

    val exoPlayer = ExoPlayer.Builder(this)
      .setHandleAudioBecomingNoisy(true)
      .setMediaSourceFactory(
        DefaultMediaSourceFactory(this)
          .setDataSourceFactory(AudioPlayerCache.getDataSourceFactory(this))
      )
      .build()
    player = exoPlayer

    val sessionBuilder = MediaSession.Builder(this, exoPlayer)

    // Tapping the notification opens the host app's main screen
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    if (launchIntent != null) {
      val contentIntent = PendingIntent.getActivity(
        this,
        0,
        launchIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )
      sessionBuilder.setSessionActivity(contentIntent)
    }

    mediaSession = sessionBuilder.build()

    setMediaNotificationProvider(DismissAwareNotificationProvider(this))

    ContextCompat.registerReceiver(
      this,
      dismissReceiver,
      IntentFilter(ACTION_STOP_FROM_NOTIFICATION),
      ContextCompat.RECEIVER_NOT_EXPORTED
    )
  }

  override fun onGetSession(
    controllerInfo: MediaSession.ControllerInfo
  ): MediaSession? = mediaSession

  override fun onTaskRemoved(rootIntent: Intent?) {
    stopPlaybackAndService()
    super.onTaskRemoved(rootIntent)
  }

  private fun stopPlaybackAndService() {
    player?.let {
      it.stop()
      it.clearMediaItems()
    }
    stopSelf()
  }

  override fun onDestroy() {
    try {
      unregisterReceiver(dismissReceiver)
    } catch (_: IllegalArgumentException) {
      // already unregistered
    }

    mediaSession?.run {
      player.release()
      release()
      mediaSession = null
    }
    player = null
    super.onDestroy()
  }
}