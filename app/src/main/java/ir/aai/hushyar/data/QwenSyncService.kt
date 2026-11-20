package ir.aai.hushyar.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import ir.aai.hushyar.HushyarApp
import ir.aai.hushyar.domain.QwenSync
import kotlin.concurrent.thread

/**
 * همگام‌سازیِ ۲۴ ساعتهٔ دانشِ اسکنر Qwen.
 * دانشِ خودِ اپ (IronPin) دست‌نخورده می‌ماند؛ این سرویس فقط هر ۲۴ ساعت
 * همان JSON دقیقِ روشِ Qwen را از https://qwen.alisport0.qzz.io می‌گیرد
 * و بدون دوباره-چانک وارد جدول knowledge می‌کند.
 *
 * جداگانه از ProactiveJobService است چون بازه‌اش ۲۴ ساعته است
 * (آن‌یکی ۱۵ دقیقه‌ای‌ست) و منطقش فقط fetch+import است.
 */
class QwenSyncService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as? HushyarApp ?: return false
        thread(name = "qwen-sync-job") {
            var reschedule = false
            try {
                // اگر هنوز ۲۴ ساعت نگذشته، کاری نکن (idempotent)
                if (QwenSync.shouldSync(app.repos)) {
                    val result = QwenSync.fetchAndImport(app.repos, HttpUrlTransport())
                    result.onFailure { e ->
                        app.repos.log("qwen-sync", "system", "همگام‌سازی ۲۴ساعته ناموفق", e.message ?: e.javaClass.simpleName)
                        reschedule = true
                    }
                }
            } catch (t: Throwable) {
                reschedule = true
                app.repos.log("qwen-sync", "system", "همگام‌سازی شکست خورد", t.message ?: t.javaClass.simpleName)
            } finally {
                jobFinished(params, reschedule)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 4712

        fun ensure(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, QwenSyncService::class.java))
                .setPeriodic(QwenSync.SYNC_PERIOD_MS, QwenSync.SYNC_FLEX_MS)
                .setBackoffCriteria(5 * 60 * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(info) }
        }

        fun cancel(context: Context) {
            (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler)?.cancel(JOB_ID)
        }

        /** برای دکمهٔ «همگام‌سازی اکنون» — بدون انتظار برای زمان‌بند. */
        fun syncNow(context: Context, onDone: (Result<QwenSync.ImportResult>) -> Unit) {
            thread(name = "qwen-sync-now") {
                val app = context.applicationContext as? HushyarApp ?: return@thread
                val res = QwenSync.fetchAndImport(app.repos, HttpUrlTransport())
                onDone(res)
            }
        }
    }
}
