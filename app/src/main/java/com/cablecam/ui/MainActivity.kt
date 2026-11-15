package com.cablecam.ui

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReport.install(this)
        CrashReport.pending(this)?.let { setContentView(CrashReport.screen(this, it)); return }
        setContentView(TextView(this).apply { text = "Cable Cam" })
    }
}
