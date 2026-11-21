package dev.lprakashv.cleanwall;

interface IRunningApps {
    String[] runningPackages() = 0;
    boolean forceStop(String packageName) = 1;
    void destroy() = 16777114;
}
