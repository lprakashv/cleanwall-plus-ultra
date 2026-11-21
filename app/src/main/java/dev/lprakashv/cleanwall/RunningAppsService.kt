package dev.lprakashv.cleanwall

import android.app.ActivityManager
import android.os.IBinder
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs under Shizuku's shell identity, not the launcher's identity. */
class RunningAppsService : IRunningApps.Stub() {
    override fun runningPackages(): Array<String> {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java).invoke(null, "activity") as IBinder
        val activityManager = Class.forName("android.app.IActivityManager")
        val stub = Class.forName("android.app.IActivityManager\$Stub")
        val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val currentUser = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
        val processes = activityManager.getMethod("getRunningAppProcesses").invoke(service) as? List<*>
            ?: emptyList<Any>()
        return runningPackagesForUser(currentUser, processes.filterIsInstance<ActivityManager.RunningAppProcessInfo>()
            .map { it.uid to it.pkgList?.toList().orEmpty() }).toTypedArray()
    }

    override fun forceStop(packageName: String): Boolean {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")))
        val process = ProcessBuilder("/system/bin/am", "force-stop", "--user", "current", packageName)
            .redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0
    }

    override fun destroy() = kotlin.system.exitProcess(0)
}

internal fun runningPackagesForUser(user: Int, processes: List<Pair<Int, List<String>>>): Set<String> =
    processes.asSequence().filter { it.first / 100000 == user }.flatMap { it.second.asSequence() }.toSet()
