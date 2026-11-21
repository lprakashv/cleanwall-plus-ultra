package dev.lprakashv.cleanwall

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

internal class RunningApps(context: Context, private val changed: (Set<String>?) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val args = Shizuku.UserServiceArgs(ComponentName(context, RunningAppsService::class.java))
        .daemon(false).processNameSuffix("running_apps").version(1)
    private var service: IRunningApps? = null
    private var bound = false
    private var visible = false
    private var checking = false
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            if (visible) handler.postDelayed(this, 10_000)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IRunningApps.Stub.asInterface(binder)
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            changed(null)
        }
    }
    private val received = Shizuku.OnBinderReceivedListener { if (visible) bind() }
    private val dead = Shizuku.OnBinderDeadListener {
        service = null
        bound = false
        changed(null)
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) bind() else changed(null)
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
    }

    fun resume() {
        visible = true
        bind()
        handler.removeCallbacks(poll)
        poll.run()
    }

    fun pause() {
        visible = false
        handler.removeCallbacks(poll)
    }

    fun requestAccess(): Boolean = runCatching {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
        else if (Shizuku.shouldShowRequestPermissionRationale()) return false
        else Shizuku.requestPermission(1)
        true
    }.getOrDefault(false)

    private fun bind() {
        if (bound) return
        val ready = runCatching {
            Shizuku.pingBinder() && !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!ready) {
            changed(null)
            return
        }
        bound = runCatching { Shizuku.bindUserService(args, connection); true }.getOrDefault(false)
        if (!bound) changed(null)
    }

    fun refresh() {
        val current = service ?: return
        if (!visible || checking) return
        checking = true
        worker.execute {
            val result = runCatching { current.runningPackages().toSet() }.getOrNull()
            handler.post {
                checking = false
                if (visible && service === current) changed(result)
            }
        }
    }

    fun forceStop(packageName: String, done: (Boolean) -> Unit) {
        val current = service ?: return done(false)
        worker.execute {
            val success = runCatching { current.forceStop(packageName) }.getOrDefault(false)
            handler.post {
                done(success)
                refresh()
            }
        }
    }

    fun close() {
        pause()
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permission)
        if (bound) runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
        worker.shutdownNow()
    }
}
