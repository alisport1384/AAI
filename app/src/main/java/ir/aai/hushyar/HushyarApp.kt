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
    }
}
