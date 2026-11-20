package ir.aai.hushyar

import android.app.Application
import ir.aai.hushyar.data.Db
import ir.aai.hushyar.data.ProactiveJobService
import ir.aai.hushyar.data.Repos
import ir.aai.hushyar.data.Store

class HushyarApp : Application() {

    lateinit var repos: Repos
        private set

    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        repos = Repos(Db(this))
        store = Store(this)
        repos.log("app", "system", "اجرای اپ", "نسخه ${BuildConfig.VERSION_NAME}")
        // Keep the "explore without being asked" job registered; it is idempotent.
        ProactiveJobService.ensure(this)
        // دانشِ اسکنر Qwen: هر ۲۴ ساعت دقیقاً همان JSONِ روش Qwen را به‌روز کن
        // (دانشِ خودِ اپ دست‌نخورده می‌ماند؛ فقط اضافه/refresh می‌شود)
        ir.aai.hushyar.data.QwenSyncService.ensure(this)
        // سیدِ اولیهٔ آفلاینِ بَچِ اسکنر (همراهِ APK) — یک‌بار، آفلاین، بدون اینترنت
        // سنگین است (242 نکته) — هرگز روی تردِ اصلی نه، وگرنه صفحه سیاه/ANR می‌دهد
        if (ir.aai.hushyar.domain.QwenSync.needsSeed(repos)) {
            Thread(name = "qwen-seed") {
                runCatching {
                    val json = assets.open("qwen_batch_bb9e0626.json").bufferedReader().readText()
                    ir.aai.hushyar.domain.QwenSync.importFromAssetsJson(json, repos)
                }.onFailure { e ->
                    runCatching { repos.log("qwen-sync", "system", "سیدِ باندل ناموفق", e.message ?: e.javaClass.simpleName) }
                }
            }.start()
        }
    }
}
