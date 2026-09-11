// SPDX-License-Identifier: AGPL-3.0-only
package io.github.gblandro.p5m

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Guarda a pilha durante um travamento, sem depender da UI para abrir o log.
 * Usa a thread de diagnostico existente, so enquanto ha uma activity visivel.
 */
class UiWatchdog(private val context: Context, private val worker: Handler) {
	private val main = Handler(Looper.getMainLooper())
	@Volatile private var pendingAt = 0L
	private var reported = false
	private val acknowledge = Runnable { pendingAt = 0L }
	private val probe = object: Runnable {
		override fun run() {
			val now = SystemClock.uptimeMillis()
			val since = pendingAt
			if(since == 0L) {
				pendingAt = now
				reported = false
				main.post(acknowledge)
			} else if(!reported && now - since >= 4000) {
				reported = true
				val stack = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "  at $it" }
				Trace.log(context, "UI stalled for ${now - since} ms; main thread:\n$stack")
			}
			worker.postDelayed(this, 2000)
		}
	}
	fun start() = worker.post {
		worker.removeCallbacks(probe)
		pendingAt = 0L
		probe.run()
	}
	fun stop() = worker.post {
		worker.removeCallbacks(probe)
		main.removeCallbacks(acknowledge)
		pendingAt = 0L
	}
}
