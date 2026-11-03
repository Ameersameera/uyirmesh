package com.ameer.uyirmesh.util

import com.ameer.uyirmesh.Graph
import kotlinx.coroutines.CoroutineExceptionHandler

/** Background errors are recorded in the diagnostics instead of closing the app. */
val CrashGuard = CoroutineExceptionHandler { _, e -> Graph.note("background task", e) }
