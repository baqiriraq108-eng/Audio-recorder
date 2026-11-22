package com.example.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.R

/**
 * BroadcastReceiver for notification action buttons so Save (`Σ Save`), Reject (`Θ Don't`),
 * Pause/Resume, Bookmark, and Playback controls bypass background `PendingIntent.getService`
 * start restrictions after the app is cleared from Recent Apps.
 */
class RecordingNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context?.applicationContext ?: return
        val action = intent?.action ?: return

        val liveService = RecordingForegroundService.activeInstance
        if (liveService != null) {
            liveService.dispatchActionOnMainThread(action, intent)
            return
        }

        // Resilient fallback if the Service instance was not currently attached
        when (action) {
            RecordingForegroundService.ACTION_DISMISS_FINISHED_NOTIFICATION -> {
                RecordingIslandOverlayManager.dismissBothFinishedNotificationAndOverlay(ctx)
            }
            RecordingForegroundService.ACTION_NOTIFICATION_CANCEL_APPEND_SAVE,
            RecordingForegroundService.ACTION_NOTIFICATION_DISCARD,
            RecordingForegroundService.ACTION_DISCARD_RECORDING -> {
                RecordingIslandOverlayManager.dismissAndReset()
                try {
                    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.cancel(RecordingForegroundService.NOTIFICATION_ID)
                    nm?.cancel(RecordingForegroundService.FINISHED_NOTIFICATION_ID)
                } catch (_: Exception) {}
                val handler = RecordingForegroundService.activeCancelAppendSaveHandler
                    ?: RecordingForegroundService.activeDiscardHandler
                if (handler != null) {
                    handler.invoke()
                } else {
                    try {
                        ctx.sendBroadcast(
                            Intent(RecordingForegroundService.ACTION_USER_CANCEL_APPEND_SAVE)
                                .setPackage(ctx.packageName)
                        )
                    } catch (_: Exception) {}
                }
            }
            RecordingForegroundService.ACTION_NOTIFICATION_CONFIRM_APPEND_SAVE -> {
                val customTitle = intent.getStringExtra(RecordingForegroundService.EXTRA_TITLE)
                val resolvedTitle = customTitle?.trim()?.takeIf { it.isNotEmpty() }
                    ?: RecordingIslandOverlayManager.currentEditedRecordingTitle.trim().takeIf { it.isNotEmpty() }
                RecordingIslandOverlayManager.showSavingProgressOverlay(ctx)
                val withTitle = RecordingForegroundService.activeConfirmAppendSaveWithTitleHandler
                val plain = RecordingForegroundService.activeConfirmAppendSaveHandler
                if (withTitle != null) {
                    withTitle.invoke(resolvedTitle)
                } else if (plain != null) {
                    plain.invoke()
                } else {
                    RecordingForegroundService.postFinishedNotificationFallback(
                        context = ctx,
                        title = ctx.getString(R.string.notification_finished_recording)
                    )
                    RecordingIslandOverlayManager.showFinishedCompletionOverlay(
                        context = ctx,
                        title = ctx.getString(R.string.notification_finished_recording)
                    )
                    try {
                        val bIntent = Intent(RecordingForegroundService.ACTION_USER_CONFIRM_APPEND_SAVE)
                            .setPackage(ctx.packageName)
                        if (!resolvedTitle.isNullOrBlank()) {
                            bIntent.putExtra(RecordingForegroundService.EXTRA_TITLE, resolvedTitle)
                        }
                        ctx.sendBroadcast(bIntent)
                    } catch (_: Exception) {}
                }
            }
            RecordingForegroundService.ACTION_NOTIFICATION_STOP_SAVE -> {
                RecordingForegroundService.activeStopSaveHandler?.invoke() ?: try {
                    ctx.sendBroadcast(
                        Intent(RecordingForegroundService.ACTION_USER_STOP_SAVE).setPackage(ctx.packageName)
                    )
                } catch (_: Exception) {}
            }
            RecordingForegroundService.ACTION_NOTIFICATION_PAUSE -> {
                RecordingForegroundService.activePauseHandler?.invoke() ?: try {
                    ctx.sendBroadcast(
                        Intent(RecordingForegroundService.ACTION_USER_PAUSE).setPackage(ctx.packageName)
                    )
                } catch (_: Exception) {}
            }
            RecordingForegroundService.ACTION_NOTIFICATION_RESUME -> {
                RecordingForegroundService.activeResumeHandler?.invoke() ?: try {
                    ctx.sendBroadcast(
                        Intent(RecordingForegroundService.ACTION_USER_RESUME).setPackage(ctx.packageName)
                    )
                } catch (_: Exception) {}
            }
            RecordingForegroundService.ACTION_NOTIFICATION_BOOKMARK -> {
                RecordingForegroundService.activeBookmarkHandler?.invoke() ?: try {
                    ctx.sendBroadcast(
                        Intent(RecordingForegroundService.ACTION_USER_BOOKMARK).setPackage(ctx.packageName)
                    )
                } catch (_: Exception) {}
            }
            RecordingForegroundService.ACTION_NOTIFICATION_PLAYBACK_PLAY_PAUSE -> {
                RecordingForegroundService.activePlaybackPlayPauseHandler?.invoke() ?: try {
                    ctx.sendBroadcast(
                        Intent(RecordingForegroundService.ACTION_USER_PLAYBACK_PLAY_PAUSE)
                            .setPackage(ctx.packageName)
                    )
                } catch (_: Exception) {}
            }
            RecordingForegroundService.ACTION_NOTIFICATION_PLAYBACK_PREV -> {
                RecordingForegroundService.activePlaybackPrevHandler?.invoke()
            }
            RecordingForegroundService.ACTION_NOTIFICATION_PLAYBACK_NEXT -> {
                RecordingForegroundService.activePlaybackNextHandler?.invoke()
            }
            RecordingForegroundService.ACTION_NOTIFICATION_PLAYBACK_BOOKMARK -> {
                RecordingForegroundService.activePlaybackBookmarkHandler?.invoke()
            }
            RecordingForegroundService.ACTION_NOTIFICATION_PLAYBACK_CONTINUE_REC -> {
                RecordingForegroundService.activePlaybackContinueRecHandler?.invoke()
            }
        }
    }
}
