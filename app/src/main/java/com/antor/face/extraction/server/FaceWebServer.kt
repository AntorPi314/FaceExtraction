package com.antor.face.extraction.server

import android.content.Context
import com.antor.face.extraction.utils.FileManager
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FaceWebServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/"                -> serveIndexPage()
            uri.startsWith("/images/") -> serveImage(uri)
            uri == "/api/stats"       -> serveStats()
            uri == "/people.json"     -> servePeopleJson()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_HTML, "<h1>404 Not Found</h1>")
        }
    }

    private fun serveIndexPage(): Response {
        val maleFiles   = FileManager.getMaleFiles(context)
        val femaleFiles = FileManager.getFemaleFiles(context)
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, buildIndexHtml(maleFiles, femaleFiles))
    }

    private fun serveImage(uri: String): Response {
        return try {
            val parts = uri.removePrefix("/images/").split("/")
            if (parts.size != 2) return notFound()
            val (gender, filename) = parts
            val dir = if (gender == "male") FileManager.getMaleDir(context) else FileManager.getFemaleDir(context)
            val file = File(dir, filename)
            if (!file.exists()) return notFound()
            newChunkedResponse(Response.Status.OK, "image/jpeg", FileInputStream(file))
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun serveStats(): Response {
        val male = FileManager.getMaleCount(context)
        val female = FileManager.getFemaleCount(context)
        val resp = newFixedLengthResponse(Response.Status.OK, "application/json",
            """{"male":$male,"female":$female,"total":${male + female}}""")
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun servePeopleJson(): Response {
        val maleFiles   = FileManager.getMaleFiles(context)
        val femaleFiles = FileManager.getFemaleFiles(context)
        val base        = getBaseUrl()
        val ts          = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())

        fun urls(gender: String, files: List<File>) =
            files.joinToString(",\n    ") { "\"$base/images/$gender/${it.name}\"" }

        val json = buildString {
            append("{\n")
            append("  \"male\": [\n")
            if (maleFiles.isNotEmpty()) append("    ${urls("male", maleFiles)}\n")
            append("  ],\n")
            append("  \"female\": [\n")
            if (femaleFiles.isNotEmpty()) append("    ${urls("female", femaleFiles)}\n")
            append("  ],\n")
            append("  \"male_count\": ${maleFiles.size},\n")
            append("  \"female_count\": ${femaleFiles.size},\n")
            append("  \"total\": ${maleFiles.size + femaleFiles.size},\n")
            append("  \"timestamp\": \"$ts\"\n")
            append("}")
        }

        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun getBaseUrl(): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) {
                val s = String.format("%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
                "http://$s:${this.listeningPort}"
            } else "http://127.0.0.1:${this.listeningPort}"
        } catch (e: Exception) { "http://127.0.0.1:${this.listeningPort}" }
    }

    private fun notFound() =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_HTML, "<h1>Not Found</h1>")

    // ── Single page: male + female together ──────────────────────────────────
    private fun buildIndexHtml(maleFiles: List<File>, femaleFiles: List<File>): String {
        val totalMale   = maleFiles.size
        val totalFemale = femaleFiles.size

        fun imgCards(gender: String, files: List<File>): String {
            if (files.isEmpty()) return """<p class="empty">No faces yet</p>"""
            return files.joinToString("\n") { file ->
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
                """<div class="img-card"><img src="/images/$gender/${file.name}" loading="lazy"/><div class="ts">$time</div></div>"""
            }
        }

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Face Extraction</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'Segoe UI',sans-serif;background:#0a0a0f;color:#fff;min-height:100vh}
header{display:flex;align-items:center;justify-content:space-between;padding:1.2rem 2rem;border-bottom:1px solid #1a1a25;position:sticky;top:0;background:#0a0a0f;z-index:10}
h1{font-size:1.3rem;letter-spacing:1px;font-weight:800}
.badge{display:flex;gap:.5rem}
.pill{padding:.25rem .9rem;border-radius:20px;font-size:.75rem;font-weight:700;letter-spacing:.5px}
.pill.m{background:#0d1f35;color:#4a90e2;border:1px solid #1a3a6a}
.pill.f{background:#2d0a1f;color:#e24a90;border:1px solid #6a1a3a}
.pill.api{background:#0a2d1f;color:#4ae2a0;border:1px solid #1a6a3a;text-decoration:none}
.dot{width:8px;height:8px;border-radius:50%;background:#4caf50;display:inline-block;margin-right:6px;animation:pulse 2s infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.3}}
.sections{display:grid;grid-template-columns:1fr 1fr;gap:0;height:calc(100vh - 61px)}
.section{padding:1.2rem;overflow-y:auto;border-right:1px solid #1a1a25}
.section:last-child{border-right:none}
.sec-header{display:flex;align-items:baseline;gap:.7rem;margin-bottom:1rem;padding-bottom:.6rem;border-bottom:1px solid #1a1a25}
.sec-title{font-size:1rem;font-weight:700}
.sec-title.m{color:#4a90e2}
.sec-title.f{color:#e24a90}
.count{font-size:.8rem;color:#555}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(130px,1fr));gap:.7rem}
.img-card{background:#111118;border-radius:10px;overflow:hidden;border:1px solid #1e1e2a;transition:all .2s}
.img-card:hover{border-color:#4a90e2;transform:scale(1.02)}
.section:last-child .img-card:hover{border-color:#e24a90}
.img-card img{width:100%;aspect-ratio:1;object-fit:cover;display:block}
.ts{font-size:.65rem;color:#555;text-align:center;padding:.3rem}
.empty{color:#333;font-size:.85rem;padding:2rem 0;text-align:center}
@media(max-width:600px){.sections{grid-template-columns:1fr;height:auto}.section{height:auto}}
</style>
<script>setTimeout(()=>location.reload(),15000);async function tick(){try{const r=await fetch('/api/stats');const d=await r.json();document.getElementById('mc').textContent=d.male+' Male';document.getElementById('fc').textContent=d.female+' Female';}catch(e){}}setInterval(tick,5000);</script>
</head>
<body>
<header>
  <h1><span class="dot"></span>Face Extraction</h1>
  <div class="badge">
    <span class="pill m" id="mc">$totalMale Male</span>
    <span class="pill f" id="fc">$totalFemale Female</span>
    <a class="pill api" href="/people.json" target="_blank">{ } JSON</a>
  </div>
</header>
<div class="sections">
  <div class="section">
    <div class="sec-header">
      <span class="sec-title m">Male</span>
      <span class="count">$totalMale faces</span>
    </div>
    <div class="grid">
      ${imgCards("male", maleFiles)}
    </div>
  </div>
  <div class="section">
    <div class="sec-header">
      <span class="sec-title f">Female</span>
      <span class="count">$totalFemale faces</span>
    </div>
    <div class="grid">
      ${imgCards("female", femaleFiles)}
    </div>
  </div>
</div>
</body>
</html>"""
    }
}
