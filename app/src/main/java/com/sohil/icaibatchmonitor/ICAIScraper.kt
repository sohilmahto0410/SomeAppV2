package com.sohil.icaibatchmonitor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Scrapes the ICAI Online Registration Portal using a hidden WebView.
 *
 * WHY WEBVIEW:
 * The portal is ASP.NET WebForms. Every dropdown change fires __doPostBack(),
 * which submits the form via JavaScript and reloads the page with new data.
 * OkHttp + Jsoup cannot execute JavaScript, so the dropdowns always came back
 * empty. A WebView runs the site's own JS engine, making the page behave exactly
 * as it does in a real browser.
 *
 * THREADING:
 * WebView MUST be created and manipulated on the main thread.
 * All public methods call withContext(Dispatchers.Main) internally, so callers
 * may invoke them from any coroutine dispatcher (IO, Default, etc.).
 *
 * URL: https://www.icaionlineregistration.org/LaunchBatchDetail.aspx
 */
class ICAIScraper(private val context: Context) {

    companion object {
        const val BASE_URL = "https://www.icaionlineregistration.org/LaunchBatchDetail.aspx"
        private const val PAGE_TIMEOUT_MS  = 30_000L
        private const val POLL_INTERVAL_MS = 300L
        private const val MAX_POLL_TRIES   = 40   // 40 × 300 ms = 12 s max
    }

    /**
     * Kept for API compatibility with existing callers.
     * WebView stores session state internally — no ViewState tokens needed.
     */
    data class FormFields(val stub: String = "")

    data class DropdownOption(val value: String, val label: String) {
        override fun toString() = label
    }

    data class BatchInfo(
        val batchNo: String,
        val startDate: String,
        val endDate: String,
        val venue: String,
        val availableSeats: String,
        val status: String,
        val rawColumns: List<String> = emptyList()
    ) {
        fun uniqueKey() = "$batchNo|$startDate|$endDate|$venue"

        fun looksAvailable(): Boolean {
            val seats = availableSeats.trim().toIntOrNull()
            return when {
                seats != null                                    -> seats > 0
                status.contains("open",      ignoreCase = true) -> true
                status.contains("available", ignoreCase = true) -> true
                else                                            -> false
            }
        }
    }

    // ─── WebView lifecycle ────────────────────────────────────────────────────────

    private var _webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(): WebView =
        _webView ?: WebView(context).also { wv ->
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            _webView = wv
        }

    /** Call after the scraper is no longer needed to free the WebView's resources. */
    fun destroy() {
        _webView?.destroy()
        _webView = null
    }

    // ─── Low-level WebView helpers (main thread only) ─────────────────────────────

    /** Navigate to [url] and suspend until onPageFinished fires. */
    private suspend fun loadAndWait(url: String) = suspendCancellableCoroutine<Unit> { cont ->
        val wv = getOrCreateWebView()
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame && cont.isActive)
                    cont.resumeWithException(
                        Exception("WebView load error: ${error.description}")
                    )
            }
        }
        wv.loadUrl(url)
    }

    /**
     * Change [dropdownId]'s value in the live DOM and call __doPostBack so
     * ASP.NET submits the form and reloads the page with updated dependent
     * dropdowns. Suspends until onPageFinished fires for the resulting page.
     */
    private suspend fun postbackAndWait(dropdownId: String, value: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            val wv = getOrCreateWebView()
            // Register the new client BEFORE injecting JS so we never miss the event.
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (request.isForMainFrame && cont.isActive)
                        cont.resumeWithException(
                            Exception("Postback error: ${error.description}")
                        )
                }
            }
            val safe = value.replace("\\", "\\\\").replace("'", "\\'")
            wv.evaluateJavascript(
                """
                (function() {
                    var el = document.getElementById('$dropdownId')
                           || document.querySelector('select[name="$dropdownId"]');
                    if (!el) return;
                    el.value = '$safe';
                    if (typeof __doPostBack === 'function') {
                        __doPostBack('$dropdownId', '');
                    } else {
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                        var f = document.forms[0];
                        if (f) f.submit();
                    }
                })();
                """.trimIndent(), null
            )
        }

    /** Evaluate a JS snippet and return the raw evaluateJavascript callback value. */
    private suspend fun evalJs(js: String): String = suspendCancellableCoroutine { cont ->
        getOrCreateWebView().evaluateJavascript(js) { result ->
            if (cont.isActive) cont.resume(result ?: "null")
        }
    }

    // ─── DOM extraction helpers ───────────────────────────────────────────────────

    /**
     * Extract <select> options as [[value, label], ...].
     * Returning a native array from JS means evaluateJavascript gives us a clean
     * JSON array without the extra string-escaping layer.
     */
    private suspend fun extractOptions(selectId: String): List<DropdownOption> {
        val raw = evalJs(
            """
            (function() {
                var sel = document.getElementById('$selectId')
                        || document.querySelector('select[name="$selectId"]');
                if (!sel) return [];
                var out = [];
                for (var i = 0; i < sel.options.length; i++) {
                    var v = (sel.options[i].value || '').trim();
                    var t = (sel.options[i].text  || '').trim();
                    if (v && v !== '0') out.push([v, t]);
                }
                return out;
            })()
            """.trimIndent()
        )
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val pair = arr.getJSONArray(i)
                DropdownOption(pair.getString(0), pair.getString(1))
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Poll [selectId] until it has at least one option.
     * Needed because after a postback the DOM update can briefly lag behind
     * onPageFinished.
     */
    private suspend fun pollForOptions(selectId: String): List<DropdownOption> {
        repeat(MAX_POLL_TRIES) {
            val opts = extractOptions(selectId)
            if (opts.isNotEmpty()) return opts
            delay(POLL_INTERVAL_MS)
        }
        return emptyList()
    }

    /** Scrape the batch results GridView into a list of BatchInfo. */
    private suspend fun extractBatches(): List<BatchInfo> {
        val raw = evalJs(
            """
            (function() {
                var table = document.getElementById('ContentPlaceHolder1_gvBatch');
                if (!table) {
                    var tables = document.getElementsByTagName('table');
                    for (var t = 0; t < tables.length; t++) {
                        if (tables[t].getElementsByTagName('tr').length > 1) {
                            table = tables[t];
                            break;
                        }
                    }
                }
                if (!table) return [];
                var rows = table.getElementsByTagName('tr');
                var out = [];
                for (var i = 1; i < rows.length; i++) {
                    var cells = rows[i].getElementsByTagName('td');
                    if (cells.length < 3) continue;
                    var cols = [];
                    for (var j = 0; j < cells.length; j++) {
                        cols.push((cells[j].innerText || '').trim());
                    }
                    out.push(cols);
                }
                return out;
            })()
            """.trimIndent()
        )
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val colArr = arr.getJSONArray(i)
                val cols = (0 until colArr.length()).map { j -> colArr.getString(j) }
                BatchInfo(
                    batchNo        = cols.getOrElse(0) { "" },
                    startDate      = cols.getOrElse(1) { "" },
                    endDate        = cols.getOrElse(2) { "" },
                    venue          = cols.getOrElse(3) { "" },
                    availableSeats = cols.getOrElse(4) { "" },
                    status         = cols.getOrElse(5) { "" },
                    rawColumns     = cols
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    // ─── Public API ───────────────────────────────────────────────────────────────

    /** Load the page fresh and return region dropdown options. */
    suspend fun getRegions(): Pair<FormFields, List<DropdownOption>> =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                loadAndWait(BASE_URL)
                val regions = pollForOptions("ddlRegion")
                Pair(FormFields(), regions)
            } ?: throw Exception("Timed out loading regions")
        }

    /**
     * Trigger the region postback and return POU options.
     * [fields] is unused — kept for API compatibility.
     */
    suspend fun getPOUs(
        fields: FormFields,
        regionValue: String
    ): Pair<FormFields, List<DropdownOption>> =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                postbackAndWait("ddlRegion", regionValue)
                val pous = pollForOptions("ddlPou")
                Pair(FormFields(), pous)
            } ?: throw Exception("Timed out loading POUs")
        }

    /**
     * Trigger the POU postback and return course options.
     * [regionValue] is unused — WebView already has the region set.
     */
    suspend fun getCourses(
        fields: FormFields,
        regionValue: String,
        pouValue: String
    ): Pair<FormFields, List<DropdownOption>> =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                postbackAndWait("ddlPou", pouValue)
                val courses = pollForOptions("ddlCourse")
                Pair(FormFields(), courses)
            } ?: throw Exception("Timed out loading courses")
        }

    /**
     * Full sequence: fresh page load → region → POU → course → scrape results.
     * Used by the background WorkManager worker.
     */
    suspend fun getBatches(region: String, pou: String, course: String): List<BatchInfo> =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(PAGE_TIMEOUT_MS * 4) {
                loadAndWait(BASE_URL)
                pollForOptions("ddlRegion")          // ensure page is ready

                postbackAndWait("ddlRegion", region)
                pollForOptions("ddlPou")

                postbackAndWait("ddlPou", pou)
                pollForOptions("ddlCourse")

                postbackAndWait("ddlCourse", course)
                delay(800)                           // let the GridView render

                extractBatches()
            } ?: throw Exception("Timed out fetching batches")
        }
}
