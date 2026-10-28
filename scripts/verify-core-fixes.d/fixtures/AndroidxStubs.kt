package androidx.core.content

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

object ContextCompat {
    @JvmStatic
    fun startForegroundService(context: Context, intent: Intent) {}
    @JvmStatic
    fun checkSelfPermission(context: Context, permission: String): Int = 0
}

open class FileProvider {
    companion object {
        @JvmStatic
        fun getUriForFile(context: Context, authority: String, file: File): Uri = Uri.EMPTY
    }
}
