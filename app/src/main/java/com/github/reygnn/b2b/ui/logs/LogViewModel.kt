package com.github.reygnn.b2b.ui.logs

import androidx.lifecycle.ViewModel
import com.github.reygnn.b2b.diagnostics.LogBuffer
import com.github.reygnn.b2b.diagnostics.LogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class LogViewModel @Inject constructor(
    private val buffer: LogBuffer,
) : ViewModel() {

    val entries: StateFlow<List<LogEntry>> = buffer.entries

    fun clear() = buffer.clear()
}
