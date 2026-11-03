package com.weekd.miracastreceiver.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 应用内切换界面语言（支持中英文一键切换，兼容所有 Android TV 版本）。
 */
object AppLanguage {

    const val TAG_ZH = "zh-CN"
    const val TAG_EN = "en"

    fun isEnglish(context: Context): Boolean {
        val tag = AppSettings.getLanguageTag(context)
        return if (tag.isNotEmpty()) {
            tag == TAG_EN
        } else {
            val current = context.resources.configuration.locales[0]
            current.language == "en"
        }
    }

    /**
     * 切换语言。同时设置 AppCompatLocales 和强制更新 Resources Configuration，
     * 确保 Android 9/10/11 等各大电视系统均能百分之百即时生效。
     */
    fun apply(context: Context, tag: String) {
        AppSettings.setLanguageTag(context, tag)
        val locale = if (tag == TAG_EN) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
        Locale.setDefault(locale)

        val res = context.resources
        val config = Configuration(res.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)

        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun restore(context: Context) {
        val tag = AppSettings.getLanguageTag(context)
        if (tag.isNotEmpty()) {
            val locale = if (tag == TAG_EN) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
            Locale.setDefault(locale)
            val res = context.resources
            val config = Configuration(res.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocales(LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                config.locale = locale
            }
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }

    fun wrap(base: Context): Context {
        val tag = AppSettings.getLanguageTag(base)
        val locale = if (tag == TAG_EN) Locale.ENGLISH else if (tag == TAG_ZH) Locale.SIMPLIFIED_CHINESE else return base
        val config = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return base.createConfigurationContext(config)
    }
}
