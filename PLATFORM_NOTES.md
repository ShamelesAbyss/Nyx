# Platform notes used for NYX 0.4.0

## Termux RUN_COMMAND

- Official documentation: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
- The protocol uses an explicit `com.termux.app.RunCommandService` intent, the `com.termux.permission.RUN_COMMAND` permission, and a caller-supplied result `PendingIntent`; Termux requires the user to enable external apps. Package visibility is declared.
- The one-shot result is a callback bundle. Adapter tests distinguish process exit code from Termux infrastructure errors and cap captured output.
- The adapter sends argv arrays, not a concatenated shell string. It requests background execution and uses callback wait timeouts. Current cancellation only ends NYX's wait and does not prove the remote Termux process has stopped.
- RUN_COMMAND runs with Termux's authority, not NYX's app-private filesystem access. No direct access to Termux private paths is assumed. Debian uses the documented PRoot-Distro form `proot-distro login debian -- /bin/uname -a` (source: https://github.com/termux/proot-distro), but the current app has not verified this end to end.

## Android long-running work

- Android WorkManager long-running task guidance: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- Android foreground-service type and restrictions: https://developer.android.com/develop/background-work/services/fgs/service-types
- NYX v0.4.0 does not yet use a Worker or foreground service. Do not claim unrestricted 24-hour execution; platform background restrictions and foreground-service type/notification rules must be designed and device-tested before this is added.
