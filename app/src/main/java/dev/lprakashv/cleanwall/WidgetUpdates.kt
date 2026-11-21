package dev.lprakashv.cleanwall

import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class WidgetUpdates(
    private val handler: Handler,
    private val display: (WidgetSource, Snapshot) -> Unit
) {
    data class Snapshot(
        val values: Map<String, String>? = null,
        val loading: Boolean = false,
        val failed: Boolean = false
    )

    private inner class Entry(val source: WidgetSource.Remote) {
        val interval = Runnable { refresh(this) }
        var started: Long? = null
        var work: Future<*>? = null
        var snapshot = Snapshot()
    }

    private val worker = Executors.newFixedThreadPool(2)
    private var entries = emptyMap<WidgetSource.Remote, Entry>()
    private var visible = false

    fun bind(specs: List<WidgetSpec>) {
        val previous = entries
        val sources = specs.mapNotNull { it.source as? WidgetSource.Remote }.distinct()
        entries = sources.associateWith { previous[it] ?: Entry(it) }
        previous.filterKeys { it !in entries }.values.forEach(::remove)
        entries.values.forEach { entry ->
            display(entry.source, entry.snapshot)
            if (visible && entry.source !in previous) show(entry)
        }
    }

    fun resume() {
        visible = true
        entries.values.forEach { entry ->
            display(entry.source, entry.snapshot)
            show(entry)
        }
    }

    fun pause() {
        visible = false
        entries.values.forEach { handler.removeCallbacks(it.interval) }
    }

    fun close() {
        entries.values.forEach(::remove)
        entries = emptyMap()
        worker.shutdownNow()
    }

    fun select(source: WidgetSource.Remote) {
        val entry = entries[source] ?: return
        if (WidgetSource.Hook.SELECT in entry.source.hooks) refresh(entry)
    }

    private fun show(entry: Entry) {
        if (WidgetSource.Hook.SHOW in entry.source.hooks) refresh(entry) else schedule(entry)
    }

    private fun schedule(entry: Entry) {
        if (!visible || entry.work != null) return
        val delay = entry.source.intervalDelay(SystemClock.elapsedRealtime(), entry.started) ?: return
        handler.postDelayed(entry.interval, delay)
    }

    private fun refresh(entry: Entry) {
        if (!visible || entry.work != null) return
        handler.removeCallbacks(entry.interval)
        entry.started = SystemClock.elapsedRealtime()
        update(entry, entry.snapshot.copy(loading = true))
        entry.work = worker.submit {
            val values = runCatching { entry.source.load() }.getOrNull()
            handler.post {
                if (entries[entry.source] !== entry) return@post
                entry.work = null
                update(entry, Snapshot(values ?: entry.snapshot.values, failed = values == null))
                schedule(entry)
            }
        }
    }

    private fun update(entry: Entry, snapshot: Snapshot) {
        entry.snapshot = snapshot
        if (visible) display(entry.source, snapshot)
    }

    private fun remove(entry: Entry) {
        handler.removeCallbacks(entry.interval)
        entry.work?.cancel(true)
    }
}
