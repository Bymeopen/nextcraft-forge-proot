package androidx.core.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context

class NotificationCompat {
    class BigTextStyle {
        fun bigText(cs: CharSequence?): BigTextStyle = this
    }
    class Builder(context: Context, channelId: String) {
        fun setSmallIcon(icon: Int): Builder = this
        fun setContentTitle(title: CharSequence?): Builder = this
        fun setContentText(text: CharSequence?): Builder = this
        fun setStyle(style: BigTextStyle?): Builder = this
        fun setContentIntent(intent: PendingIntent?): Builder = this
        fun setCategory(category: String?): Builder = this
        fun setOnlyAlertOnce(onlyAlertOnce: Boolean): Builder = this
        fun setOngoing(ongoing: Boolean): Builder = this
        fun setAutoCancel(autoCancel: Boolean): Builder = this
        fun setPriority(priority: Int): Builder = this
        fun setProgress(max: Int, progress: Int, indeterminate: Boolean): Builder = this
        fun setForegroundServiceBehavior(behavior: Int): Builder = this
        fun addAction(icon: Int, title: CharSequence?, intent: PendingIntent?): Builder = this
        fun build(): Notification = Notification()
    }
    companion object {
        const val CATEGORY_PROGRESS = "progress"
        const val CATEGORY_ERROR = "err"
        const val CATEGORY_STATUS = "status"
        const val PRIORITY_LOW = -1
        const val PRIORITY_DEFAULT = 0
        const val FOREGROUND_SERVICE_IMMEDIATE = 1
    }
}
