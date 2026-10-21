package com.breakoverlay.app.domain

import android.graphics.drawable.Drawable

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isLauncher: Boolean = false,
)
