package com.z150x.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Arabic / English, chosen in onboarding and toggled from Home. AppCompat recreates open screens. */
object AppLanguage {
    fun isArabic(ctx: Context): Boolean = ctx.resources.configuration.locales[0].language == "ar"

    /** Arabic keeps Western digits (0-9) in numbers, dates and units, like the strings do. */
    fun set(lang: String) {
        val tag = if (lang == "ar") "ar-u-nu-latn" else lang
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun toggle(ctx: Context) = set(if (isArabic(ctx)) "en" else "ar")
}
