package com.sohil.icaibatchmonitor

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * Scrapes the ICAI Online Registration Portal.
 * URL: https://www.icaionlineregistration.org/LaunchBatchDetail.aspx
 *
 * NOTE: Jsoup's Elements class has its own .filter(NodeFilter) method that conflicts
 * with Kotlin's collection .filter{}. Always call .toList() on Elements before
 * using any Kotlin lambda extensions (.filter{}, .map{}, .firstOrNull{}).
 */
class ICAIScraper {

    companion object {
        const val BASE_URL = "https://www.icaionlineregistration.org/LaunchBatchDetail.aspx"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", BASE_URL)
                .build()
            chain.proceed(request)
        }
        .build()

    data class FormFields(
        val viewState: String = "",
        val viewStateGenerator: String = "",
        val eventValidation: String = ""
    )

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
                seats != null -> seats > 0
                status.contains("open", ignoreCase = true) -> true
                status.contains("available", ignoreCase = true) -> true
                else -> false
            }
        }
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────────

    private fun extractFormFields(doc: Document) = FormFields(
        viewState = doc.select("input[name=__VIEWSTATE]").attr("value"),
        viewStateGenerator = doc.select("input[name=__VIEWSTATEGENERATOR]").attr("value"),
        eventValidation = doc.select("input[name=__EVENTVALIDATION]").attr("value")
    )

    private fun buildBody(
        fields: FormFields,
        eventTarget: String,
        region: String,
        pou: String,
        course: String
    ): FormBody = FormBody.Builder()
        .add("__EVENTTARGET", eventTarget)
        .add("__EVENTARGUMENT", "")
        .add("__LASTFOCUS", "")
        .add("__VIEWSTATE", fields.viewState)
        .add("__VIEWSTATEGENERATOR", fields.viewStateGenerator)
        .add("__EVENTVALIDATION", fields.eventValidation)
        .add("ddlRegion", region)
        .add("ddlPou", pou)
        .add("ddlCourse", course)
        .build()

    private fun get(url: String): Document {
        val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
        val html = resp.body?.string() ?: throw Exception("Empty response")
        return Jsoup.parse(html, url)
    }

    private fun post(
        fields: FormFields,
        eventTarget: String,
        region: String,
        pou: String,
        course: String
    ): Document {
        val body = buildBody(fields, eventTarget, region, pou, course)
        val resp = client.newCall(Request.Builder().url(BASE_URL).post(body).build()).execute()
        val html = resp.body?.string() ?: throw Exception("Empty POST response")
        return Jsoup.parse(html, BASE_URL)
    }

    /**
     * Parse dropdown options from a CSS selector.
     * .toList() MUST be called on Elements before .filter{} or .map{} to avoid
     * Jsoup's NodeFilter.filter() method hijacking Kotlin's lambda syntax.
     */
    private fun parseOptions(doc: Document, selector: String): List<DropdownOption> {
        val elements = doc.select(selector).toList()
        val result = mutableListOf<DropdownOption>()
        for (el in elements) {
            val value = el.attr("value")
            if (value.isNotBlank() && value != "0") {
                result.add(DropdownOption(value, el.text().trim()))
            }
        }
        return result
    }

    // ─── Public API ───────────────────────────────────────────────────────────────

    fun getRegions(): Pair<FormFields, List<DropdownOption>> {
        val doc = get(BASE_URL)
        return Pair(
            extractFormFields(doc),
            parseOptions(doc, "#ddlRegion option, select[name=ddlRegion] option")
        )
    }

    fun getPOUs(fields: FormFields, regionValue: String): Pair<FormFields, List<DropdownOption>> {
        val doc = post(fields, "ddlRegion", regionValue, "", "")
        return Pair(
            extractFormFields(doc),
            parseOptions(doc, "#ddlPou option, select[name=ddlPou] option")
        )
    }

    fun getCourses(
        fields: FormFields,
        regionValue: String,
        pouValue: String
    ): Pair<FormFields, List<DropdownOption>> {
        val doc = post(fields, "ddlPou", regionValue, pouValue, "")
        return Pair(
            extractFormFields(doc),
            parseOptions(doc, "#ddlCourse option, select[name=ddlCourse] option")
        )
    }

    fun getBatches(region: String, pou: String, course: String): List<BatchInfo> {
        val (f1, _) = getRegions()
        val (f2, _) = getPOUs(f1, region)
        val (f3, _) = getCourses(f2, region, pou)
        val doc = post(f3, "ddlCourse", region, pou, course)
        return parseBatches(doc)
    }

    private fun parseBatches(doc: Document): List<BatchInfo> {
        val batches = mutableListOf<BatchInfo>()

        // .toList() before firstOrNull{} — same Jsoup conflict prevention
        val allTables = doc.select(
            "#ContentPlaceHolder1_gvBatch, table[id*=Grid], table[id*=grid], " +
            "table.gridView, table[class*=Grid], table[class*=grid], table"
        ).toList()

        val table = allTables.firstOrNull { tbl ->
            tbl.select("tr:first-child th, tr:first-child td").size >= 3
        } ?: return emptyList()

        val rows = table.select("tr").toList()

        // Skip header row at index 0
        for (i in 1 until rows.size) {
            val row = rows[i]
            val cells = row.select("td").toList()
            if (cells.size < 3) continue

            val cols = mutableListOf<String>()
            for (cell in cells) {
                cols.add(cell.text().trim())
            }

            batches.add(BatchInfo(
                batchNo        = cols.getOrElse(0) { "" },
                startDate      = cols.getOrElse(1) { "" },
                endDate        = cols.getOrElse(2) { "" },
                venue          = cols.getOrElse(3) { "" },
                availableSeats = cols.getOrElse(4) { "" },
                status         = cols.getOrElse(5) { "" },
                rawColumns     = cols
            ))
        }

        return batches
    }
}
