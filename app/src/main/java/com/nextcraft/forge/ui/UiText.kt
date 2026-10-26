package com.nextcraft.forge.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Text produced outside a composable (ViewModel, enums) that still needs translating.
 * [Res] carries a string id plus format args; [Raw] is for text that is already final
 * (server replies, exception messages) and must not be looked up.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText

    /** [context] may be null only for [Raw], which needs no lookup; [Res] requires a real Context. */
    fun asString(context: Context?): String = when (this) {
        is Res -> requireNotNull(context) { "UiText.Res needs a Context" }.getString(id, *args.toTypedArray())
        // Raw is final text, except that fixed English sentences from lower layers are translated when shown.
        is Raw -> if (context == null) text else localizeKnownText(context, text)
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Res -> stringResource(id, *args.toTypedArray())
    is UiText.Raw -> localizeKnownText(androidx.compose.ui.platform.LocalContext.current, text)
}

/** [raw] in the app language when it is a sentence the app itself produced in English (see MessageText). */
internal fun localizeKnownText(context: Context, raw: String): String {
    val known = MessageText.match(raw) ?: return raw
    return context.getString(known.first, *known.second.toTypedArray())
}

/** An error's own text when it has one, else the translated fallback. */
fun String?.orRes(@StringRes id: Int, vararg args: Any): UiText = this?.let(UiText::Raw) ?: UiText.res(id, *args)

/** Failure whose message is meant for the person using the app: carries translatable text instead of English. */
class UiTextException(val text: UiText) : Exception()

/** Translated text of a [UiTextException]; for any other failure its own message (capped), else the fallback. */
fun Throwable.toUiText(@StringRes fallback: Int): UiText =
    (this as? UiTextException)?.text ?: message?.take(240).orRes(fallback)

/** Plural-aware string for Compose: "1 file" vs "2 files" (and one form only in languages without plurals). */
@androidx.compose.runtime.Composable
fun quantityStringResource(@androidx.annotation.PluralsRes id: Int, quantity: Int, vararg args: Any): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.ui.platform.LocalConfiguration.current // re-read when the locale changes
    return context.resources.getQuantityString(id, quantity, *args)
}
