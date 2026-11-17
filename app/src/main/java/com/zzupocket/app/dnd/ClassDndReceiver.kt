package com.zzupocket.app.dnd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zzupocket.app.core.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ClassDndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val controller = AppGraph.classDndController
                when (intent.action) {
                    ClassCountdownNotification.ACTION_HIDE ->
                        intent.data?.lastPathSegment?.toLongOrNull()?.let { controller.hideReminder(it) }
                    ClassCountdownNotification.ACTION_DISMISS -> {
                        val parts = intent.data?.pathSegments.orEmpty()
                        val start = parts.getOrNull(0)?.toLongOrNull()
                        val deadline = parts.getOrNull(1)?.toLongOrNull()
                        if (start != null && deadline != null && System.currentTimeMillis() < deadline) {
                            controller.hideReminder(start)
                        }
                    }
                    else -> controller.refresh()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
