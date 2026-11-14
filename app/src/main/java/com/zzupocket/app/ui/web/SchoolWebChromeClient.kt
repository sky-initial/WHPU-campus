package com.zzupocket.app.ui.web

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient

/** School scripts can print authentication state. Consume console output without logging it. */
class SchoolWebChromeClient : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
}
