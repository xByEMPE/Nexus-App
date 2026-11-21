package mx.com.nexusio.fly.ui

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDexApplication
import com.cySdkyc.clx.Helper

class MyApplication : MultiDexApplication() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        try {
            Helper.install(this as Application)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}