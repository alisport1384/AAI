package ir.aai.hushyar

import android.app.Application
import ir.aai.hushyar.data.Db
import ir.aai.hushyar.data.Repos

class HushyarApp : Application() {

    lateinit var repos: Repos
        private set

    override fun onCreate() {
        super.onCreate()
        repos = Repos(Db(this))
    }
}
