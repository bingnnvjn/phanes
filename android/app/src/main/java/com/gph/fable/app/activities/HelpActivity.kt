package com.gph.fable.app.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import androidx.appcompat.app.AppCompatActivity
import com.gph.fable.shared.termux.TermuxConstants
import com.gph.fable.shared.view.SystemBarInsets

/** Basic embedded browser for viewing help pages. */
open class HelpActivity : AppCompatActivity() {

    @JvmField
    var mWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val progressLayout = RelativeLayout(this)
        val layoutParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT)
        val progressBar = ProgressBar(this)
        progressBar.isIndeterminate = true
        progressBar.layoutParams = layoutParams
        progressLayout.addView(progressBar)

        mWebView = WebView(this)
        mWebView!!.settings.cacheMode = WebSettings.LOAD_NO_CACHE

        val webViewContainer = FrameLayout(this)
        webViewContainer.addView(
            mWebView!!,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        SystemBarInsets.applyAllSystemBarInsets(webViewContainer)

        setContentView(progressLayout)
        SystemBarInsets.applyAllSystemBarInsets(progressLayout)
        mWebView!!.clearCache(true)

        mWebView!!.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url == TermuxConstants.TERMUX_WIKI_URL ||
                    url.startsWith(TermuxConstants.TERMUX_WIKI_URL + "/")
                ) {
                    setContentView(progressLayout)
                    return false
                }

                return try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                } catch (_: ActivityNotFoundException) {
                    setContentView(progressLayout)
                    false
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                setContentView(webViewContainer)
            }
        }
        mWebView!!.loadUrl(TermuxConstants.TERMUX_WIKI_URL)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (mWebView!!.canGoBack()) {
            mWebView!!.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
