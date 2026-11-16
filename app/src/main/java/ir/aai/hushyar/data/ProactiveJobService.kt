package ir.aai.hushyar.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import ir.aai.hushyar.HushyarApp
import ir.aai.hushyar.domain.SearchCore
import kotlin.concurrent.thread

/**
 * The "does not wait to be asked" scheduler.
 *
 * Why [JobScheduler] and not WorkManager: WorkManager would add a dependency, and dependencies are
 * a protected decision here. The platform job scheduler gives the same guarantee that matters —
 * *minimum* 15-minute periodicity ([JobInfo.getMinPeriodMillis]), delayed or skipped runs allowed,
 * no promise of exact timing —
 * with zero new libraries and no extra permission. Everything is idempotent, so a skipped run costs
 * nothing: the cursor in `plan_state` decides where the next run continues.
 */
class ProactiveJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as? HushyarApp ?: return false
        thread(name = "proactive-job") {
            var reschedule = false
            try {
                val tools = AndroidTools(app.repos, app.store, SearchCore(HttpUrlTransport()))
                ProactiveRunner(app.repos, tools).tick()
            } catch (t: Throwable) {
                reschedule = true
                app.repos.log("research", "system", "کاوش خودکار شکست خورد", t.message ?: t.javaClass.simpleName)
            } finally {
                jobFinished(params, reschedule)
            }
        }
        return true
    }

    /** The OS wants the job back (e.g. constraints lost): stop, and let the next run resume. */
    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 4711
        private const val PERIOD_MS = 15 * 60 * 1000L
        private const val FLEX_MS = 5 * 60 * 1000L

        /** Idempotent: scheduling twice keeps one job, never two researchers at once. */
        fun ensure(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            // Deliberately *no* network constraint: since Android 14 a job that requires a network
            // needs ACCESS_NETWORK_STATE, and this app asks for no permission beyond INTERNET.
            // Without the constraint the run may happen while offline — which is harmless, because
            // every query fails softly and nothing is written; the cursor simply stays where it was.
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, ProactiveJobService::class.java))
                .setPeriodic(PERIOD_MS, FLEX_MS)
                .setBackoffCriteria(5 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            runCatching { scheduler.schedule(info) }
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            scheduler.cancel(JOB_ID)
        }
    }
}
