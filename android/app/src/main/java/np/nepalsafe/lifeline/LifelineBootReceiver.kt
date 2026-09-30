package np.nepalsafe.lifeline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class LifelineBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED) ||
            !LifelineService.wasActive(context)
        ) return

        runCatching {
            context.startForegroundService(
                Intent(context, LifelineService::class.java).setAction(LifelineService.ACTION_START)
            )
        }.onFailure { error ->
            Log.e(TAG, "Could not restore the active Lifeline service", error)
        }
    }

    private companion object {
        const val TAG = "LifelineBootReceiver"
    }
}
