package com.antor.face.extraction.server

import com.antor.face.extraction.utils.FileManager
import com.antor.face.extraction.utils.NetworkUtils
import fi.iki.elonen.NanoHTTPD
import android.content.Context
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
            uri == "/"                 -> serveIndexPage()
            uri.startsWith("/images/") -> serveImage(uri)
            uri == "/api/stats"        -> serveStats()
            uri == "/people.json"      -> servePeopleJson()
            uri == "/all.json"         -> serveAllJson()
            uri == "/captured.jpg"     -> serveCapturedJpg()
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
            // Support both /images/male/file.jpg and /images/all/male/file.jpg
            val (dir, filename) = when {
                parts.size == 2 -> {
                    val (gender, file) = parts
                    val d = when (gender) {
                        "male"   -> FileManager.getMaleDir(context)
                        "female" -> FileManager.getFemaleDir(context)
                        else     -> return notFound()
                    }
                    Pair(d, file)
                }
                parts.size == 3 && parts[0] == "all" -> {
                    val (_, gender, file) = parts
                    val d = when (gender) {
                        "male"   -> FileManager.getAllMaleDir(context)
                        "female" -> FileManager.getAllFemaleDir(context)
                        else     -> return notFound()
                    }
                    Pair(d, file)
                }
                else -> return notFound()
            }

            // ✅ Path traversal protection:
            // canonical path check করে নিশ্চিত করি যে requested file
            // আসলেই allowed directory এর ভেতরে আছে।
            // এটা "../../../etc/passwd" style attack ঠেকায়।
            val file = File(dir, filename)
            val canonicalFile = file.canonicalPath
            val canonicalDir  = dir.canonicalPath
            if (!canonicalFile.startsWith(canonicalDir + File.separator)) {
                return forbidden()
            }

            if (!file.exists()) return notFound()
            newChunkedResponse(Response.Status.OK, "image/jpeg", FileInputStream(file))
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun serveStats(): Response {
        val male   = FileManager.getMaleCount(context)
        val female = FileManager.getFemaleCount(context)
        val allMale   = FileManager.getAllMaleCount(context)
        val allFemale = FileManager.getAllFemaleCount(context)
        val resp = newFixedLengthResponse(
            Response.Status.OK, "application/json",
            """{"male":$male,"female":$female,"total":${male + female},"all_male":$allMale,"all_female":$allFemale,"all_count":${allMale + allFemale}}"""
        )
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

    private fun serveAllJson(): Response {
        val allMaleFiles   = FileManager.getAllMaleFiles(context)
        val allFemaleFiles = FileManager.getAllFemaleFiles(context)
        val base           = getBaseUrl()
        val ts             = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())

        fun urls(gender: String, files: List<File>) =
            files.joinToString(",\n    ") { "\"$base/images/all/$gender/${it.name}\"" }

        val json = buildString {
            append("{\n")
            append("  \"male\": [\n")
            if (allMaleFiles.isNotEmpty()) append("    ${urls("male", allMaleFiles)}\n")
            append("  ],\n")
            append("  \"female\": [\n")
            if (allFemaleFiles.isNotEmpty()) append("    ${urls("female", allFemaleFiles)}\n")
            append("  ],\n")
            append("  \"male_count\": ${allMaleFiles.size},\n")
            append("  \"female_count\": ${allFemaleFiles.size},\n")
            append("  \"all_count\": ${allMaleFiles.size + allFemaleFiles.size},\n")
            append("  \"timestamp\": \"$ts\"\n")
            append("}")
        }

        val resp = newFixedLengthResponse(Response.Status.OK, "application/json", json)
        resp.addHeader("Access-Control-Allow-Origin", "*")
        return resp
    }

    private fun serveCapturedJpg(): Response {
        val file = FileManager.getCapturedFile(context)
        return if (file.exists()) {
            val resp = newChunkedResponse(Response.Status.OK, "image/jpeg", FileInputStream(file))
            resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            resp.addHeader("Access-Control-Allow-Origin", "*")
            resp
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_HTML, "<h1>No capture yet</h1>")
        }
    }

    // ✅ আর duplicate IP logic নেই — সরাসরি NetworkUtils ব্যবহার করছে
    private fun getBaseUrl(): String {
        val ip = NetworkUtils.getWifiIpAddress(context)
        return "http://$ip:${this.listeningPort}"
    }

    private fun notFound() =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_HTML, "<h1>Not Found</h1>")

    private fun forbidden() =
        newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_HTML, "<h1>403 Forbidden</h1>")

    private fun buildIndexHtml(maleFiles: List<File>, femaleFiles: List<File>): String {
        val totalMale   = maleFiles.size
        val totalFemale = femaleFiles.size

        fun imgCards(gender: String, files: List<File>): String {
            if (files.isEmpty()) return """<p class="empty">No faces yet</p>"""
            return files.joinToString("\n") { file ->
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
                """<div class="img-card" data-name="${file.name}"><img src="/images/$gender/${file.name}" loading="lazy"/><div class="ts">$time</div></div>"""
            }
        }

        val favicon = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' rx='14' fill='%230A0A0F'/%3E%3Cline x1='0' y1='16' x2='64' y2='16' stroke='%234A90E212' stroke-width='1'/%3E%3Cline x1='0' y1='32' x2='64' y2='32' stroke='%234A90E212' stroke-width='1'/%3E%3Cline x1='0' y1='48' x2='64' y2='48' stroke='%234A90E212' stroke-width='1'/%3E%3Cpath d='M10 4L4 4L4 10' fill='none' stroke='%234A90E2' stroke-width='3' stroke-linecap='round'/%3E%3Cpath d='M54 4L60 4L60 10' fill='none' stroke='%234A90E2' stroke-width='3' stroke-linecap='round'/%3E%3Cpath d='M10 60L4 60L4 54' fill='none' stroke='%234A90E2' stroke-width='3' stroke-linecap='round'/%3E%3Cpath d='M54 60L60 60L60 54' fill='none' stroke='%234A90E2' stroke-width='3' stroke-linecap='round'/%3E%3Ccircle cx='4' cy='4' r='2.5' fill='%234A90E2'/%3E%3Ccircle cx='60' cy='4' r='2.5' fill='%234A90E2'/%3E%3Ccircle cx='4' cy='60' r='2.5' fill='%234A90E2'/%3E%3Ccircle cx='60' cy='60' r='2.5' fill='%234A90E2'/%3E%3Cellipse cx='32' cy='32' rx='14' ry='17' fill='none' stroke='%23d0d0e8' stroke-width='2'/%3E%3Ccircle cx='25' cy='27' r='2.8' fill='%234A90E2'/%3E%3Ccircle cx='39' cy='27' r='2.8' fill='%234A90E2'/%3E%3Ccircle cx='25' cy='27' r='5' fill='none' stroke='%234A90E2' stroke-width='1.5'/%3E%3Ccircle cx='39' cy='27' r='5' fill='none' stroke='%234A90E2' stroke-width='1.5'/%3E%3Cpath d='M32 33 L30 39 Q32 40.5 34 39' fill='none' stroke='%23777' stroke-width='1.5' stroke-linecap='round'/%3E%3Cpath d='M25 44 Q32 50 39 44' fill='none' stroke='%23d0d0e8' stroke-width='1.8' stroke-linecap='round'/%3E%3C/svg%3E"

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Face Extraction</title>
<link rel="icon" type="image/svg+xml" href="$favicon">
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'Segoe UI',sans-serif;background:#0a0a0f;color:#fff;min-height:100vh}
#err-bar{display:none;position:fixed;bottom:0;left:0;right:0;z-index:200;background:#1a0a0a;border-top:1px solid #6a1a1a;padding:.35rem 1rem;font-size:.72rem;color:#e24a4a;letter-spacing:.3px}
header{display:flex;align-items:center;justify-content:space-between;gap:.6rem;padding:.9rem 1.2rem;border-bottom:1px solid #1a1a25;position:sticky;top:0;background:#0a0a0f;z-index:10;flex-wrap:wrap}
h1{font-size:1.15rem;letter-spacing:.5px;font-weight:800;white-space:nowrap;display:flex;align-items:center;gap:.4rem}
.dot{width:7px;height:7px;border-radius:50%;background:#4caf50;display:inline-block;animation:pulse 2s infinite;flex-shrink:0}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.3}}
.badge{display:flex;gap:.4rem;align-items:center;overflow-x:auto;-webkit-overflow-scrolling:touch;padding-bottom:2px;flex-shrink:1;min-width:0}
.badge::-webkit-scrollbar{display:none}
.pill{padding:.22rem .75rem;border-radius:20px;font-size:.7rem;font-weight:700;letter-spacing:.4px;white-space:nowrap;flex-shrink:0}
.pill.m{background:#0d1f35;color:#4a90e2;border:1px solid #1a3a6a}
.pill.f{background:#2d0a1f;color:#e24a90;border:1px solid #6a1a3a}
.pill.a{background:#1a0a2d;color:#9a6ae2;border:1px solid #3a1a6a}
.pill.api{background:#0a2d1f;color:#4ae2a0;border:1px solid #1a6a3a;text-decoration:none}
.pill.cap{background:#1a1a0a;color:#e2c44a;border:1px solid #6a5a1a;text-decoration:none}
.tabs{display:none;border-bottom:1px solid #1a1a25;background:#0a0a0f;position:sticky;top:53px;z-index:9}
.tab-btn{flex:1;padding:.65rem 0;font-size:.8rem;font-weight:700;letter-spacing:.5px;background:none;border:none;color:#555566;cursor:pointer;border-bottom:2px solid transparent;transition:all .2s}
.tab-btn.active-m{color:#4a90e2;border-color:#4a90e2}
.tab-btn.active-f{color:#e24a90;border-color:#e24a90}
.sections{display:grid;grid-template-columns:1fr 1fr;height:calc(100vh - 55px);overflow:hidden}
.section{padding:1rem;overflow-y:auto;border-right:1px solid #1a1a25}
.section:last-child{border-right:none}
.sec-header{display:flex;align-items:baseline;gap:.6rem;margin-bottom:.9rem;padding-bottom:.5rem;border-bottom:1px solid #1a1a25;position:sticky;top:0;background:#0a0a0f;z-index:5;padding-top:.2rem}
.sec-title{font-size:.95rem;font-weight:700}
.sec-title.m{color:#4a90e2}
.sec-title.f{color:#e24a90}
.count{font-size:.75rem;color:#555}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(120px,1fr));gap:.6rem}
.img-card{background:#111118;border-radius:10px;overflow:hidden;border:1px solid #1e1e2a;transition:all .2s}
.img-card:hover{transform:scale(1.02)}
#grid-male .img-card:hover{border-color:#4a90e2}
#grid-female .img-card:hover{border-color:#e24a90}
.img-card img{width:100%;aspect-ratio:1;object-fit:cover;display:block}
.ts{font-size:.6rem;color:#555;text-align:center;padding:.25rem}
.empty{color:#333;font-size:.82rem;padding:2rem 0;text-align:center}
@media(max-width:650px){
  header{padding:.7rem .9rem;top:0}
  h1{font-size:1rem}
  .tabs{display:flex}
  .sections{grid-template-columns:1fr;height:calc(100vh - 97px);overflow:hidden}
  .section{display:none;height:100%;overflow-y:auto;border-right:none}
  .section.active{display:block}
  .sec-header{top:0}
  .grid{grid-template-columns:repeat(auto-fill,minmax(100px,1fr));gap:.5rem}
}
</style>
</head>
<body>
<div id="err-bar"></div>
<header>
  <h1><span class="dot"></span>Face Extraction</h1>
  <div class="badge">
    <span class="pill m" id="mc">$totalMale Male</span>
    <span class="pill f" id="fc">$totalFemale Female</span>
    <span class="pill a" id="ac">${totalMale + totalFemale} All</span>
    <a class="pill api" href="/people.json" target="_blank">{ } JSON</a>
    <a class="pill api" href="/all.json" target="_blank">{ } All</a>
    <a class="pill cap" href="/captured.jpg" target="_blank">Captured</a>
  </div>
</header>
<div class="tabs">
  <button class="tab-btn active-m" id="tab-m" onclick="switchTab('m')">Male <span id="tab-mc">$totalMale</span></button>
  <button class="tab-btn" id="tab-f" onclick="switchTab('f')">Female <span id="tab-fc">$totalFemale</span></button>
</div>
<div class="sections">
  <div class="section active" id="sec-male">
    <div class="sec-header">
      <span class="sec-title m">Male</span>
      <span class="count" id="male-count-label">$totalMale faces</span>
    </div>
    <div class="grid" id="grid-male">
      ${imgCards("male", maleFiles)}
    </div>
  </div>
  <div class="section" id="sec-female">
    <div class="sec-header">
      <span class="sec-title f">Female</span>
      <span class="count" id="female-count-label">$totalFemale faces</span>
    </div>
    <div class="grid" id="grid-female">
      ${imgCards("female", femaleFiles)}
    </div>
  </div>
</div>
<script>
function switchTab(gender) {
  document.getElementById('sec-male').classList.toggle('active', gender === 'm');
  document.getElementById('sec-female').classList.toggle('active', gender === 'f');
  document.getElementById('tab-m').className = 'tab-btn' + (gender === 'm' ? ' active-m' : '');
  document.getElementById('tab-f').className = 'tab-btn' + (gender === 'f' ? ' active-f' : '');
}
const errBar = document.getElementById('err-bar');
function showError(msg) {
  errBar.textContent = 'Fetch failed at ' + new Date().toTimeString().slice(0,8) + ' — ' + msg;
  errBar.style.display = 'block';
}
function clearError() { errBar.style.display = 'none'; }
function makeCard(gender, filename) {
  const src = '/images/' + gender + '/' + filename;
  let ts = '';
  const m = filename.match(/_(\d{2})(\d{2})(\d{2})_\d+\.jpg$/);
  if (m) ts = m[1] + ':' + m[2] + ':' + m[3];
  const card = document.createElement('div');
  card.className    = 'img-card';
  card.dataset.name = filename;
  card.innerHTML    = '<img src="' + src + '" loading="lazy"><div class="ts">' + ts + '</div>';
  return card;
}
function updateGrid(gridEl, gender, names) {
  const existing = new Set([...gridEl.querySelectorAll('.img-card')].map(c => c.dataset.name));
  const incoming = new Set(names);
  gridEl.querySelectorAll('.img-card').forEach(c => { if (!incoming.has(c.dataset.name)) c.remove(); });
  names.filter(n => !existing.has(n)).reverse().forEach(n => {
    gridEl.insertBefore(makeCard(gender, n), gridEl.firstChild);
  });
  if (names.length === 0 && !gridEl.querySelector('.empty'))
    gridEl.innerHTML = '<p class="empty">No faces yet</p>';
  else if (names.length > 0) { const p = gridEl.querySelector('.empty'); if (p) p.remove(); }
}
async function refresh() {
  try {
    const [sRes, pRes] = await Promise.all([
      fetch('/api/stats', { cache: 'no-store' }),
      fetch('/people.json', { cache: 'no-store' })
    ]);
    if (!sRes.ok) throw new Error('stats ' + sRes.status);
    if (!pRes.ok) throw new Error('people.json ' + pRes.status);
    const stats = await sRes.json();
    const data  = await pRes.json();
    document.getElementById('mc').textContent = stats.male   + ' Male';
    document.getElementById('fc').textContent = stats.female + ' Female';
    document.getElementById('ac').textContent = stats.total  + ' All';
    document.getElementById('male-count-label').textContent   = stats.male   + ' faces';
    document.getElementById('female-count-label').textContent = stats.female + ' faces';
    document.getElementById('tab-mc').textContent = stats.male;
    document.getElementById('tab-fc').textContent = stats.female;
    const mNames = (data.male   || []).map(u => u.split('/').pop());
    const fNames = (data.female || []).map(u => u.split('/').pop());
    updateGrid(document.getElementById('grid-male'),   'male',   mNames);
    updateGrid(document.getElementById('grid-female'), 'female', fNames);
    clearError();
  } catch(e) { showError(e.message || 'unknown'); }
}
setInterval(refresh, 5000);
</script>
</body>
</html>"""
    }
}
