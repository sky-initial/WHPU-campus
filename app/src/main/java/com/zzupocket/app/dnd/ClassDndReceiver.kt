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
            try { AppGraph.classDndController.refresh() } finally { pending.finish() }
        }
    }
}
