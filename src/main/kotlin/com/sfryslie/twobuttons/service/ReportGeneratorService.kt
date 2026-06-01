package com.sfryslie.twobuttons.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sfryslie.twobuttons.config.ScoringProperties
import com.sfryslie.twobuttons.model.Agreement
import com.sfryslie.twobuttons.model.SessionOutput
import com.sfryslie.twobuttons.model.SessionScore
import com.sfryslie.twobuttons.model.ScorerOutput
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Service
class ReportGeneratorService(private val properties: ScoringProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private data class Row(
        val score: SessionScore,
        val lang: String,
        val model: String,
        val file: String,
        val vote: String,
        val confidence: String,
        val ruleError: Boolean,
        val uds: Boolean,
        val adc: Boolean,
        val voteChanged: Boolean,
        val safetyRefusal: Boolean,
        val pdReference: Boolean,
        val impostorSignal: Boolean
    )

    fun generateReport() {
        val outDir = Paths.get(properties.outputDir)
        if (!Files.exists(outDir)) return

        val cap = properties.maxSessionsPerModelLang
        val rows = mutableListOf<Row>()
        Files.list(outDir).filter { Files.isDirectory(it) }.sorted().forEach { langDir ->
            val lang = langDir.fileName.toString()
            Files.list(langDir).filter { Files.isDirectory(it) }.sorted().forEach { modelDir ->
                val model = modelDir.fileName.toString()
                var files = Files.list(modelDir)
                    .filter { it.fileName.toString().endsWith(".score.json") }
                    .sorted()   // sort by filename = chronological (timestamp in name)
                    .toList()
                if (cap > 0 && files.size > cap) {
                    log.debug("[$lang/$model] Capping ${files.size} sessions to $cap for report")
                    files = files.take(cap)
                }
                files.forEach { f ->
                    runCatching { mapper.readValue(f.toFile(), SessionScore::class.java) }
                        .onSuccess { rows.add(derive(it, lang, model, f.fileName.toString())) }
                        .onFailure { log.warn("Failed to parse $f: ${it.message}") }
                }
            }
        }

        if (rows.isEmpty()) { log.warn("No score files found — skipping report"); return }

        val reportPath = outDir.resolve("report.html")
        Files.writeString(reportPath, buildHtml(rows))
        log.info("Report written → $reportPath")
    }

    private fun derive(score: SessionScore, lang: String, model: String, file: String): Row {
        val s = score.scores.values.filterNotNull()
        fun bool(f: (ScorerOutput) -> Boolean) = s.count { f(it) } > s.size / 2
        fun <T> maj(f: (ScorerOutput) -> T) = s.groupingBy { f(it) }.eachCount().maxByOrNull { it.value }!!.key
        return Row(
            score = score, lang = lang, model = model, file = file,
            vote = if (score.agreement == Agreement.DISAGREE) "DISAGREE" else score.majorityVote.name,
            confidence = if (s.isEmpty()) "UNKNOWN" else maj { it.confidence }.name,
            ruleError = bool { it.ruleError },
            uds = bool { it.understandsDominantStrategy },
            adc = bool { it.appliesDominanceCorrectly },
            voteChanged = bool { it.voteChanged },
            safetyRefusal = bool { it.safetyRefusal },
            pdReference = bool { it.pdReference },
            impostorSignal = bool { it.impostorSignal }
        )
    }

    private fun buildHtml(rows: List<Row>): String {
        val total = rows.size
        val languages = rows.map { it.lang }.distinct().sorted()
        val scorerNames = rows.firstOrNull()?.score?.scorers ?: emptyList()
        val scorers = scorerNames.joinToString(", ")
        val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(Instant.now())

        val disagreements = rows.filter { it.score.agreement == Agreement.DISAGREE }
        val ruleErrors    = rows.filter { it.ruleError }
        val pdRefs        = rows.filter { it.pdReference }
        val impostors     = rows.filter { it.impostorSignal }

        // Per (model, lang) stats embedded as JS DATA array for client-side filtering
        data class MLStat(
            val model: String, val lang: String,
            val n: Int,
            val votes: Map<String, Triple<Int, Int, Int>>, // scorer -> (blue, red, none)
            val ruleError: Int, val uds: Int, val adc: Int,
            val voteChanged: Int, val disagree: Int, val agree: Int,
            val pdRef: Int, val impostor: Int
        )
        val mlStats = rows.groupBy { it.model to it.lang }.map { (key, ms) ->
            MLStat(
                model = key.first, lang = key.second, n = ms.size,
                // Per-scorer vote tallies so JS can filter by scorer at render time.
                votes = scorerNames.associateWith { scorer ->
                    val outs = ms.mapNotNull { it.score.scores[scorer] }
                    Triple(
                        outs.count { it.initialVote.name == "BLUE" },
                        outs.count { it.initialVote.name == "RED"  },
                        outs.count { it.initialVote.name == "NONE" }
                    )
                },
                ruleError = ms.count { it.ruleError },
                uds       = ms.count { it.uds },
                adc       = ms.count { it.adc },
                voteChanged = ms.count { it.voteChanged },
                disagree  = ms.count { it.score.agreement == Agreement.DISAGREE },
                agree     = ms.count { it.score.agreement == Agreement.AGREE },
                pdRef     = ms.count { it.pdReference },
                impostor  = ms.count { it.impostorSignal }
            )
        }

        val dataJs = mlStats.joinToString(",\n  ") { m ->
            val esc = m.model.replace("\"", "\\\"")
            val votesJs = m.votes.entries.joinToString(",") { (sc, t) ->
                val scEsc = sc.replace("\"", "\\\"")
                "\"$scEsc\":{blue:${t.first},red:${t.second},none:${t.third}}"
            }
            "{model:\"$esc\",lang:\"${m.lang}\",n:${m.n},votes:{$votesJs}," +
            "ruleError:${m.ruleError},uds:${m.uds},adc:${m.adc},voteChanged:${m.voteChanged},disagree:${m.disagree},agree:${m.agree}," +
            "pdRef:${m.pdRef},impostor:${m.impostor}}"
        }
        val langsJs = languages.joinToString(",") { "\"$it\"" }
        val maxModels = rows.map { it.model }.distinct().size
        val chartHeight = (maxModels * 28 + 60).coerceAtLeast(200)

        // Static doughnut charts (per language, all data — not affected by filter)
        val langStats = rows.groupBy { it.lang }.entries
            .map { (lang, ls) -> mapOf("lang" to lang, "n" to ls.size,
                "blue" to ls.count { it.vote == "BLUE" },
                "red"  to ls.count { it.vote == "RED"  },
                "none" to ls.count { it.vote == "NONE" }) }
            .sortedBy { it["lang"] as String }

        fun scorerRowsFor(s: Row, cols: List<String>) =
            s.score.scores.entries.joinToString("") { (scorer, out) ->
                if (out == null) "" else buildString {
                    append("<tr class='sr'><td class='sn'>$scorer</td>")
                    if ("vote" in cols) append("<td class='c-${out.initialVote.name.lowercase()}'>${out.initialVote}</td>")
                    if ("conf" in cols) append("<td>${out.confidence}</td>")
                    if ("rule" in cols) append("<td>${if (out.ruleError) "⚠ yes" else "no"}</td>")
                    append("<td class='rt'>${out.reasoning.replace("<", "&lt;")}</td></tr>")
                }
            }

        val inputDir = Paths.get(properties.inputDir)
        val disRows = disagreements.joinToString("\n") { s ->
            val qaHtml = runCatching {
                val origPath = inputDir.resolve(s.lang).resolve(s.model)
                    .resolve(s.file.removeSuffix(".score.json") + ".json")
                val orig = mapper.readValue(origPath.toFile(), SessionOutput::class.java)
                orig.session.responses.joinToString("") { r ->
                    val q = r.question.replace("&", "&amp;").replace("<", "&lt;")
                    val a = r.response.replace("&", "&amp;").replace("<", "&lt;")
                    "<div class='qa-item'><div class='qa-q'><strong>Q${r.questionIndex + 1}</strong> &mdash; $q</div><div class='qa-a'>$a</div></div>"
                }
            }.getOrElse { "<p class='empty'>Could not load original session file: ${it.message?.replace("<","&lt;")}</p>" }

            """<details class="card-item" data-lang="${s.lang}">
<summary><span class="tag-model">${s.model}</span><span class="tag-lang">${s.lang}</span><span class="tag-file">${s.file.removeSuffix(".score.json").takeLast(40)}</span></summary>
<table class="it"><thead><tr><th>Scorer</th><th>Vote</th><th>Confidence</th><th>Rule Error</th><th>Reasoning</th></tr></thead>
<tbody>${scorerRowsFor(s, listOf("vote","conf","rule"))}</tbody></table>
<details class="qa-section"><summary class="qa-toggle">Model Responses</summary><div class="qa-body">$qaHtml</div></details>
</details>"""
        }

        val errRows = ruleErrors.joinToString("\n") { s ->
            val scorerHtml = s.score.scores.entries.filter { it.value?.ruleError == true }
                .joinToString("") { (scorer, out) ->
                    if (out == null) "" else
                    "<tr class='sr'><td class='sn'>$scorer</td>" +
                    "<td class='c-${out.initialVote.name.lowercase()}'>${out.initialVote}</td>" +
                    "<td class='rt'>${out.reasoning.replace("<", "&lt;")}</td></tr>"
                }
            """<details class="card-item" data-lang="${s.lang}">
<summary><span class="tag-model">${s.model}</span><span class="tag-lang">${s.lang}</span><span class="tag-file">${s.file.removeSuffix(".score.json").takeLast(40)}</span></summary>
<table class="it"><thead><tr><th>Scorer</th><th>Vote</th><th>Reasoning</th></tr></thead>
<tbody>$scorerHtml</tbody></table></details>"""
        }

        val pdRefRows = pdRefs.joinToString("\n") { s ->
            val scorerHtml = s.score.scores.entries.filter { it.value?.pdReference == true }
                .joinToString("") { (scorer, out) ->
                    if (out == null) "" else
                    "<tr class='sr'><td class='sn'>$scorer</td>" +
                    "<td class='c-${out.initialVote.name.lowercase()}'>${out.initialVote}</td>" +
                    "<td class='rt'>${out.reasoning.replace("<", "&lt;")}</td></tr>"
                }
            """<details class="card-item" data-lang="${s.lang}">
<summary><span class="tag-model">${s.model}</span><span class="tag-lang">${s.lang}</span><span class="tag-file">${s.file.removeSuffix(".score.json").takeLast(40)}</span></summary>
<table class="it"><thead><tr><th>Scorer</th><th>Vote</th><th>Reasoning</th></tr></thead>
<tbody>$scorerHtml</tbody></table></details>"""
        }

        val impostorRows = impostors.joinToString("\n") { s ->
            val scorerHtml = s.score.scores.entries.filter { it.value?.impostorSignal == true }
                .joinToString("") { (scorer, out) ->
                    if (out == null) "" else
                    "<tr class='sr'><td class='sn'>$scorer</td>" +
                    "<td class='c-${out.initialVote.name.lowercase()}'>${out.initialVote}</td>" +
                    "<td class='rt'>${out.reasoning.replace("<", "&lt;")}</td></tr>"
                }
            """<details class="card-item" data-lang="${s.lang}">
<summary><span class="tag-model">${s.model}</span><span class="tag-lang">${s.lang}</span><span class="tag-file">${s.file.removeSuffix(".score.json").takeLast(40)}</span></summary>
<table class="it"><thead><tr><th>Scorer</th><th>Vote</th><th>Reasoning</th></tr></thead>
<tbody>$scorerHtml</tbody></table></details>"""
        }

        val langNames = mapOf(
            "ar" to "Arabic", "de" to "German", "en" to "English", "es" to "Spanish",
            "fr" to "French", "hi" to "Hindi", "ja" to "Japanese", "ru" to "Russian",
            "zh-CN" to "Chinese (Simplified)", "zh-TW" to "Chinese (Traditional)",
            "it" to "Italian", "pt" to "Portuguese", "ko" to "Korean", "ca" to "Catalan",
            "id" to "Indonesian", "he" to "Hebrew", "tr" to "Turkish", "sw" to "Swahili",
            "bn" to "Bengali", "af" to "Afrikaans", "ur" to "Urdu", "da" to "Danish",
            "uk" to "Ukrainian", "fa" to "Persian", "el" to "Greek"
        )
        val langCheckboxes = languages.joinToString("\n") { lang ->
            val name = langNames[lang] ?: lang
            """<label class="lang-cb"><input type="checkbox" id="lang_$lang" value="$lang" checked onchange="render()"> $name ($lang)</label>"""
        }
        val scorerCheckboxes = scorerNames.joinToString("\n") { sc ->
            val id = sc.replace(Regex("[^a-zA-Z0-9]"), "_")
            val jsName = sc.replace("'", "\\'")
            """<label class="lang-cb"><input type="checkbox" id="scorer_$id" checked onchange="toggleScorer('$jsName')"> $sc</label>"""
        }

        val langDoughnuts = if (languages.size > 1) """
<section>
  <h2>Language Comparison (all data)</h2>
  <div class="lang-charts">
${langStats.joinToString("\n") { ls ->
    """    <div class="lang-chart-box"><h3>${ls["lang"]} (n=${ls["n"]})</h3><canvas id="langChart_${ls["lang"]}"></canvas></div>"""
}}
  </div>
</section>""" else ""

        val langDoughnutsJs = if (languages.size > 1) langStats.joinToString("\n") { ls ->
            val lang = ls["lang"]; val b = ls["blue"]; val r = ls["red"]; val n = ls["none"]
            """new Chart(document.getElementById('langChart_$lang'), {
  type:'doughnut', data:{labels:['BLUE','RED','NONE'],datasets:[{data:[$b,$r,$n],backgroundColor:[BLUE,RED,NONE],borderWidth:1}]},
  options:{plugins:{legend:{position:'bottom',labels:{font:{size:11}}}}}});"""
        } else ""

        return """<!DOCTYPE html>
<html lang="en"><head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Two Buttons — Scoring Report</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"></script>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#f4f5f7;color:#222;font-size:14px}
header{background:#1a1a2e;color:#fff;padding:24px 32px 20px}
header h1{font-size:20px;font-weight:600;letter-spacing:.5px;margin-bottom:4px}
header .sub{color:#8888aa;font-size:12px;margin-bottom:20px}
.stats{display:flex;gap:24px;flex-wrap:wrap}
.stat{background:rgba(255,255,255,.07);border-radius:8px;padding:14px 20px;min-width:120px}
.stat .val{font-size:28px;font-weight:700;line-height:1}
.stat .lbl{color:#8888aa;font-size:11px;margin-top:4px;text-transform:uppercase;letter-spacing:.5px}
.stat.hi .val{color:#4a90d9}
.stat.blue .val{color:#60a5fa}
.stat.red  .val{color:#f87171}
.stat.none .val{color:#9ca3af}
main{padding:24px 32px;max-width:1400px}
section{background:#fff;border-radius:8px;padding:20px 24px;margin-bottom:20px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
section h2{font-size:14px;font-weight:600;text-transform:uppercase;letter-spacing:.5px;color:#555;margin-bottom:16px;border-bottom:1px solid #eee;padding-bottom:10px}
.section-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:16px;border-bottom:1px solid #eee;padding-bottom:10px}
.section-header h2{font-size:14px;font-weight:600;text-transform:uppercase;letter-spacing:.5px;color:#555;margin:0;border:none;padding:0}
.toggle-btn{display:flex;border-radius:4px;overflow:hidden;border:1px solid #d0d0d0}
.toggle-btn button{background:#f4f5f7;border:none;padding:5px 14px;font-size:12px;font-weight:600;color:#666;cursor:pointer}
.toggle-btn button.active{background:#1a1a2e;color:#fff}
/* Sidebar layout */
.chart-section-inner{display:flex;gap:20px;align-items:flex-start}
.filter-sidebar{width:220px;flex-shrink:0;display:flex;flex-direction:column;gap:16px;position:sticky;top:16px;max-height:calc(100vh - 32px);overflow-y:auto;padding-right:2px}
.chart-area{flex:1;min-width:0}
.chart-wrap{position:relative;height:${chartHeight}px}
/* Filter groups */
.fg-label{font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.5px;color:#777;margin-bottom:6px;display:flex;align-items:center;justify-content:space-between}
.fg-label .an{font-size:10px;font-weight:400;text-transform:none;letter-spacing:0;color:#4a90d9}
.fg-label .an span{cursor:pointer}
.fg-label .an span:hover{text-decoration:underline}
.filter-checks{display:flex;flex-direction:column;gap:3px}
/* Lang checkboxes */
.lang-cb{display:flex;align-items:center;gap:6px;font-size:12px;cursor:pointer;padding:2px 4px;border-radius:3px}
.lang-cb:hover{background:#f0f0ff}
.lang-cb input{cursor:pointer;flex-shrink:0}
/* Family chips */
.family-cb{display:flex;align-items:center;gap:5px;font-size:12px;cursor:pointer;background:#f0f4ff;border-radius:4px;padding:3px 8px;border:1px solid #d0d8f0;margin-bottom:3px}
.family-cb input{cursor:pointer}
.family-cb.off{background:#f4f5f7;color:#aaa;border-color:#e0e0e0;text-decoration:line-through}
/* Weight buttons — stacked in sidebar */
.w-btn{background:#f4f5f7;border:1px solid #d0d0d0;padding:5px 10px;font-size:12px;font-weight:600;color:#666;cursor:pointer;border-radius:4px;text-align:left;width:100%;margin-bottom:3px}
.w-btn.active{background:#1a1a2e;color:#fff;border-color:#1a1a2e}
/* Model list */
.model-list{max-height:200px;overflow-y:auto;display:flex;flex-direction:column;gap:2px;border:1px solid #eee;border-radius:4px;padding:5px 6px;background:#fafafa;scrollbar-width:thin}
.model-cb{display:flex;align-items:center;gap:5px;font-size:11px;cursor:pointer;padding:1px 2px;border-radius:3px}
.model-cb:hover:not(.family-hidden){background:#f0f0ff}
.model-cb span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.model-cb input{cursor:pointer;flex-shrink:0}
.model-cb.family-hidden{opacity:0.25;pointer-events:none}
/* Tables */
table{width:100%;border-collapse:collapse;font-size:13px}
th{text-align:left;padding:8px 10px;background:#f8f9fa;border-bottom:2px solid #e0e0e0;font-weight:600;color:#555;white-space:nowrap}
td{padding:7px 10px;border-bottom:1px solid #f0f0f0}
tr:last-child td{border-bottom:none}
tr:hover td{background:#fafafa}
.c-blue{color:#2563eb;font-weight:600}.c-red{color:#dc2626;font-weight:600}.c-none{color:#666}
details.card-item{border:1px solid #e8e8e8;border-radius:6px;margin-bottom:8px;overflow:hidden}
details.card-item summary{padding:10px 14px;cursor:pointer;background:#fafafa;display:flex;align-items:center;gap:8px;user-select:none}
details.card-item summary:hover{background:#f0f0f0}
details.card-item[open] summary{background:#f0f0ff;border-bottom:1px solid #e0e0f0}
.tag-model{font-weight:600;font-size:13px}
.tag-lang{background:#e0e7ff;color:#3730a3;border-radius:4px;padding:2px 7px;font-size:11px;font-weight:600}
.tag-file{color:#888;font-size:11px;font-family:monospace;margin-left:4px}
table.it{margin:12px;width:calc(100% - 24px);font-size:12px}
.sr td{vertical-align:top}.sn{font-weight:600;color:#444;white-space:nowrap}.rt{color:#444;line-height:1.5;max-width:600px}
.lang-charts{display:flex;gap:20px;flex-wrap:wrap}
.lang-chart-box{flex:1;min-width:180px;max-width:260px}
.lang-chart-box h3{font-size:12px;font-weight:600;color:#555;text-align:center;margin-bottom:8px}
.empty{color:#aaa;font-style:italic;padding:12px 0}
/* Q&A viewer */
.qa-section{border-top:1px solid #e8e8f0;margin-top:8px}
.qa-toggle{padding:8px 12px;cursor:pointer;font-size:12px;font-weight:600;color:#5555aa;background:#f8f8ff;user-select:none;list-style:none;display:block}
.qa-toggle::-webkit-details-marker{display:none}
.qa-toggle::before{content:'▶  '}
details.qa-section[open] .qa-toggle::before{content:'▼  '}
.qa-body{padding:12px 14px;display:flex;flex-direction:column;gap:14px;max-height:600px;overflow-y:auto;background:#fefeff}
.qa-item{border-left:3px solid #c7d2f0;padding-left:10px}
.qa-q{font-size:12px;color:#444;font-style:italic;margin-bottom:4px;line-height:1.4}
.qa-a{font-size:12px;color:#222;line-height:1.6;white-space:pre-wrap;word-break:break-word}
@media(max-width:900px){
  .chart-section-inner{flex-direction:column}
  .filter-sidebar{width:100%;flex-direction:row;flex-wrap:wrap;position:static;max-height:none;overflow-y:visible}
  .filter-sidebar>div{min-width:160px;flex:1}
  .model-list{max-height:120px}
}
</style></head>
<body>
<header>
  <h1>Two Buttons — Scoring Report</h1>
  <div class="sub">Generated $ts &nbsp;·&nbsp; Scorers: $scorers &nbsp;·&nbsp; Languages: ${languages.joinToString(", ")}</div>
  <div class="stats">
    <div class="stat hi"><div class="val" id="stat-total">$total</div><div class="lbl">Sessions</div></div>
    <div class="stat blue"><div class="val" id="stat-blue"></div><div class="lbl">Blue</div></div>
    <div class="stat red"><div class="val" id="stat-red"></div><div class="lbl">Red</div></div>
    <div class="stat none"><div class="val" id="stat-none"></div><div class="lbl">None</div></div>
    <div class="stat"><div class="val" id="stat-agree"></div><div class="lbl">Scorer Agreement</div></div>
    <div class="stat"><div class="val" id="stat-disagree"></div><div class="lbl">Disagreements</div></div>
    <div class="stat"><div class="val" id="stat-rule"></div><div class="lbl">Rule Errors</div></div>
    <div class="stat"><div class="val" id="stat-voteChanged"></div><div class="lbl">Votes Changed by Q4</div></div>
    <div class="stat"><div class="val" id="stat-pd"></div><div class="lbl">PD References</div></div>
    <div class="stat"><div class="val" id="stat-impostor"></div><div class="lbl">Impostor Signals</div></div>
  </div>
</header>
<main>

<section>
  <div class="section-header">
    <h2>Vote Distribution by Model</h2>
    <div style="display:flex;gap:10px">
      <div class="toggle-btn">
        <button id="btn-raw" class="active" onclick="setMode('raw')">Raw</button>
        <button id="btn-pct" onclick="setMode('pct')">%</button>
      </div>
      <div class="toggle-btn">
        <button id="btn-sort-blue" class="active" onclick="setSort('blue')">Blue%</button>
        <button id="btn-sort-alpha" onclick="setSort('alpha')">A–Z</button>
      </div>
    </div>
  </div>
  <div class="chart-section-inner">
    <div class="filter-sidebar">
      <div>
        <div class="fg-label">Language <span class="an"><span onclick="setAllLangs(true)">All</span> · <span onclick="setAllLangs(false)">None</span></span></div>
        <div class="filter-checks" id="lang-checks">
$langCheckboxes
        </div>
      </div>
      <div>
        <div class="fg-label">Scorer <span class="an"><span onclick="setAllScorers(true)">All</span> · <span onclick="setAllScorers(false)">None</span></span></div>
        <div class="filter-checks" id="scorer-checks">
$scorerCheckboxes
        </div>
      </div>
      <div>
        <div class="fg-label">Family</div>
        <div id="family-bar"></div>
      </div>
      <div>
        <div class="fg-label">Weight</div>
        <button id="btn-w-all" class="w-btn active" onclick="setWeight('all')">All</button>
        <button id="btn-w-prop" class="w-btn" onclick="setWeight('proprietary')">Proprietary</button>
        <button id="btn-w-open" class="w-btn" onclick="setWeight('open')">Open Weight</button>
      </div>
      <div>
        <div class="fg-label">Models <span class="an"><span onclick="setAllModels(true)">All</span> · <span onclick="setAllModels(false)">None</span></span></div>
        <div class="model-list" id="model-list"></div>
      </div>
    </div>
    <div class="chart-area">
      <div class="chart-wrap" id="chart-wrap"><canvas id="voteChart"></canvas></div>
    </div>
  </div>
</section>

<section>
  <h2>Model Summary</h2>
  <table>
    <thead><tr><th>Model</th><th>N</th><th>Blue%</th><th>Red%</th><th>None%</th><th>Rule Error%</th><th>Understands Dom%</th><th>Applies Dom%</th><th>Vote Changed%</th><th>Disagree%</th><th>PD Ref%</th><th>Impostor%</th></tr></thead>
    <tbody id="modelTbody"></tbody>
  </table>
</section>
$langDoughnuts

<section>
  <h2>Scorer Disagreements (<span id="dis-count"></span>)</h2>
  <div id="dis-container">${if (disagreements.isEmpty()) "<p class='empty'>No disagreements.</p>" else disRows}</div>
</section>

<section>
  <h2>Rule Errors (<span id="err-count"></span>)</h2>
  <div id="err-container">${if (ruleErrors.isEmpty()) "<p class='empty'>No rule errors detected.</p>" else errRows}</div>
</section>

<section>
  <h2>PD / Framework Mismatches (<span id="pd-count"></span>)</h2>
  <div id="pd-container">${if (pdRefs.isEmpty()) "<p class='empty'>No Prisoner's Dilemma references detected.</p>" else pdRefRows}</div>
</section>

<section>
  <h2>Impostor Signals (<span id="imp-count"></span>)</h2>
  <div id="imp-container">${if (impostors.isEmpty()) "<p class='empty'>No impostor signals detected.</p>" else impostorRows}</div>
</section>

</main>
<script>
const BLUE='#3b82f6',RED='#ef4444',NONE='#9ca3af';
function displayName(m){return m.startsWith('ollama-')?m.slice(7)+' (Ollama)':m;}
const LANGS=[$langsJs];
const DATA=[
  $dataJs];

function getFamily(m){
  m=m.toLowerCase();
  const b=m.startsWith('ollama-')?m.slice(7):m;
  if(b.includes('claude'))return'Anthropic';
  if(b.includes('gemini')||b.includes('gemma'))return'Google';
  if(b.includes('gpt-oss')||b.includes('gpt:oss'))return'OpenAI';
  if(b.includes('gpt')||b.includes('o1-')||b.includes('o3-'))return'OpenAI';
  if(b.includes('grok'))return'xAI';
  if(b.includes('llama'))return'Meta';
  if(b.includes('mistral')||b.includes('mixtral'))return'Mistral';
  if(b.includes('deepseek'))return'DeepSeek';
  if(b.includes('nemotron'))return'Nvidia';
  if(b.includes('qwen'))return'Alibaba';
  if(b.includes('phi'))return'Microsoft';
  if(b.includes('glm'))return'Zhipu AI';
  if(b.includes('minimax'))return'MiniMax';
  if(b.includes('kimi'))return'Moonshot';
  if(b.includes('aya'))return'Cohere';
  if(b.includes('falcon'))return'TII';
  return'Other';
}
function getWeight(m){
  m=m.toLowerCase();
  // Explicitly proprietary API-only models
  if(m.startsWith('claude'))return'proprietary';
  if(m.startsWith('gpt-')&&!m.includes('oss'))return'proprietary';
  if(m.startsWith('o1')||m.startsWith('o3')||m.startsWith('o4'))return'proprietary';
  if(m.startsWith('gemini'))return'proprietary';
  if(m.startsWith('grok'))return'proprietary';
  // Everything else is open weight (local ollama, cloud-served open models, etc.)
  return'open';
}

const ALL_FAMILIES=[...new Set(DATA.map(d=>getFamily(d.model)))].sort();
const ALL_SCORERS=[...new Set(DATA.flatMap(d=>Object.keys(d.votes)))].sort();
let selFamilies=new Set(ALL_FAMILIES);
let selScorers=new Set(ALL_SCORERS);
let selModels=new Set();
let selWeight='all';
let chartMode='raw',sortMode='blue';

function sumVotes(d){
  let blue=0,red=0,none=0;
  selScorers.forEach(sc=>{const sv=d.votes[sc];if(sv){blue+=sv.blue;red+=sv.red;none+=sv.none;}});
  return {blue,red,none};
}

function getSelectedLangs(){return LANGS.filter(l=>{const el=document.getElementById('lang_'+l);return el?el.checked:true;});}
function pct(a,b){return b>0?Math.round(a*100/b):0;}
function pctf(a,b){return b>0?Math.round(a*1000/b)/10:0;}
function fmt(n,t){return n+' <span style="font-size:16px;color:#8888aa">('+pct(n,t)+'%)</span>';}

function aggregate(langs){
  const filtered=DATA.filter(d=>{
    if(!langs.includes(d.lang))return false;
    if(!selFamilies.has(getFamily(d.model)))return false;
    if(!selModels.has(d.model))return false;
    const w=getWeight(d.model);
    if(selWeight==='proprietary'&&w!=='proprietary')return false;
    if(selWeight==='open'&&w!=='open')return false;
    return true;
  });
  const byModel={};
  for(const d of filtered){
    if(!byModel[d.model])byModel[d.model]={model:d.model,n:0,blue:0,red:0,none:0,ruleError:0,uds:0,adc:0,voteChanged:0,disagree:0,agree:0,pdRef:0,impostor:0};
    const m=byModel[d.model];
    const v=sumVotes(d);
    m.n+=d.n;m.blue+=v.blue;m.red+=v.red;m.none+=v.none;
    m.ruleError+=d.ruleError;m.uds+=d.uds;m.adc+=d.adc;m.voteChanged+=d.voteChanged;m.disagree+=d.disagree;m.agree+=d.agree;
    m.pdRef+=d.pdRef;m.impostor+=d.impostor;
  }
  const sorted=Object.values(byModel);
  return sortMode==='alpha'
    ? sorted.sort((a,b)=>a.model.localeCompare(b.model))
    : sorted.sort((a,b)=>pct(b.blue,b.n)-pct(a.blue,a.n)||a.model.localeCompare(b.model));
}

function setMode(mode){
  chartMode=mode;
  document.getElementById('btn-raw').classList.toggle('active',mode==='raw');
  document.getElementById('btn-pct').classList.toggle('active',mode==='pct');
  render();
}
function setSort(mode){
  sortMode=mode;
  document.getElementById('btn-sort-blue').classList.toggle('active',mode==='blue');
  document.getElementById('btn-sort-alpha').classList.toggle('active',mode==='alpha');
  render();
}
function setWeight(w){
  selWeight=w;
  document.getElementById('btn-w-all').classList.toggle('active',w==='all');
  document.getElementById('btn-w-prop').classList.toggle('active',w==='proprietary');
  document.getElementById('btn-w-open').classList.toggle('active',w==='open');
  render();
}
function setAllLangs(v){
  LANGS.forEach(l=>{const el=document.getElementById('lang_'+l);if(el)el.checked=v;});
  render();
}
function toggleScorer(sc){
  if(selScorers.has(sc))selScorers.delete(sc);else selScorers.add(sc);
  render();
}
function setAllScorers(v){
  ALL_SCORERS.forEach(sc=>{
    const id='scorer_'+sc.replace(/[^a-zA-Z0-9]/g,'_');
    const el=document.getElementById(id);
    if(el)el.checked=v;
    if(v)selScorers.add(sc);else selScorers.delete(sc);
  });
  render();
}
function setAllModels(v){
  document.querySelectorAll('.model-cb:not(.family-hidden)').forEach(el=>{
    const model=el.dataset.model;
    el.querySelector('input').checked=v;
    if(v)selModels.add(model);else selModels.delete(model);
  });
  render();
}
function toggleFamily(fam){
  if(selFamilies.has(fam))selFamilies.delete(fam);else selFamilies.add(fam);
  document.querySelectorAll('[data-fam]').forEach(el=>{
    el.classList.toggle('off',!selFamilies.has(el.dataset.fam));
  });
  // Sync model list: grey out + deselect models from hidden families
  document.querySelectorAll('.model-cb').forEach(el=>{
    const model=el.dataset.model;
    const famVisible=selFamilies.has(getFamily(model));
    el.classList.toggle('family-hidden',!famVisible);
    const cb=el.querySelector('input');
    if(!famVisible){cb.checked=false;selModels.delete(model);}
    else{cb.checked=true;selModels.add(model);}
  });
  render();
}
function toggleModel(model){
  if(selModels.has(model))selModels.delete(model);else selModels.add(model);
  render();
}

const voteChart=new Chart(document.getElementById('voteChart'),{
  type:'bar',
  data:{labels:[],datasets:[
    {label:'BLUE',data:[],backgroundColor:BLUE},
    {label:'RED', data:[],backgroundColor:RED},
    {label:'NONE',data:[],backgroundColor:NONE}
  ]},
  options:{
    indexAxis:'y',responsive:true,maintainAspectRatio:false,
    scales:{
      x:{stacked:true,grid:{color:'#f0f0f0'},ticks:{callback:v=>chartMode==='pct'?v+'%':v}},
      y:{stacked:true,ticks:{font:{size:11}}}
    },
    plugins:{
      legend:{position:'top'},
      tooltip:{callbacks:{label:ctx=>{
        const m=ctx.dataset.label;
        const v=ctx.raw;
        return chartMode==='pct'?' '+m+': '+v+'%':' '+m+': '+v;
      }}}
    }
  }
});

function render(){
  const langs=getSelectedLangs();
  const models=aggregate(langs);
  const total=models.reduce((s,m)=>s+m.n,0);
  const tBlue=models.reduce((s,m)=>s+m.blue,0);
  const tRed=models.reduce((s,m)=>s+m.red,0);
  const tVotes=models.reduce((s,m)=>s+m.blue+m.red+m.none,0); // scorer-votes (may exceed sessions)
  const tAgree=models.reduce((s,m)=>s+m.agree,0);
  const tDis=models.reduce((s,m)=>s+m.disagree,0);
  const tRule=models.reduce((s,m)=>s+m.ruleError,0);
  const tChanged=models.reduce((s,m)=>s+m.voteChanged,0);

  document.getElementById('stat-total').textContent=total;
  const tNone=models.reduce((s,m)=>s+m.none,0);
  document.getElementById('stat-blue').textContent=pct(tBlue,tVotes)+'%';
  document.getElementById('stat-red').textContent=pct(tRed,tVotes)+'%';
  document.getElementById('stat-none').textContent=pct(tNone,tVotes)+'%';
  document.getElementById('stat-agree').textContent=pct(tAgree,total)+'%';
  document.getElementById('stat-disagree').textContent=tDis;
  document.getElementById('stat-rule').innerHTML=fmt(tRule,total);
  document.getElementById('stat-voteChanged').innerHTML=fmt(tChanged,total);
  const tPd=models.reduce((s,m)=>s+m.pdRef,0);
  const tImpostor=models.reduce((s,m)=>s+m.impostor,0);
  document.getElementById('stat-pd').innerHTML=fmt(tPd,total);
  document.getElementById('stat-impostor').innerHTML=fmt(tImpostor,total);

  document.getElementById('chart-wrap').style.height=Math.max(200,models.length*28+60)+'px';
  const isPct=chartMode==='pct';
  voteChart.data.labels=models.map(m=>displayName(m.model));
  voteChart.data.datasets[0].data=models.map(m=>isPct?pctf(m.blue,m.blue+m.red+m.none):m.blue);
  voteChart.data.datasets[1].data=models.map(m=>isPct?pctf(m.red, m.blue+m.red+m.none):m.red);
  voteChart.data.datasets[2].data=models.map(m=>isPct?pctf(m.none,m.blue+m.red+m.none):m.none);
  voteChart.options.scales.x.max=isPct?100:undefined;
  voteChart.update();

  document.getElementById('modelTbody').innerHTML=models.map(m=>
    '<tr><td>'+displayName(m.model)+'</td><td>'+m.n+'</td>'+
    '<td class="c-blue">'+pct(m.blue,m.blue+m.red+m.none)+'%</td><td class="c-red">'+pct(m.red,m.blue+m.red+m.none)+'%</td><td class="c-none">'+pct(m.none,m.blue+m.red+m.none)+'%</td>'+
    '<td>'+pct(m.ruleError,m.n)+'%</td><td>'+pct(m.uds,m.n)+'%</td><td>'+pct(m.adc,m.n)+'%</td><td>'+pct(m.voteChanged,m.n)+'%</td><td>'+pct(m.disagree,m.n)+'%</td>'+
    '<td>'+pct(m.pdRef,m.n)+'%</td><td>'+pct(m.impostor,m.n)+'%</td></tr>'
  ).join('');

  document.querySelectorAll('.card-item[data-lang]').forEach(el=>{
    el.style.display=langs.includes(el.dataset.lang)?'':'none';
  });
  let dc=0,ec=0,pc=0,ic=0;
  document.querySelectorAll('#dis-container .card-item[data-lang]').forEach(el=>{if(el.style.display!=='none')dc++;});
  document.querySelectorAll('#err-container .card-item[data-lang]').forEach(el=>{if(el.style.display!=='none')ec++;});
  document.querySelectorAll('#pd-container .card-item[data-lang]').forEach(el=>{if(el.style.display!=='none')pc++;});
  document.querySelectorAll('#imp-container .card-item[data-lang]').forEach(el=>{if(el.style.display!=='none')ic++;});
  document.getElementById('dis-count').textContent=dc;
  document.getElementById('err-count').textContent=ec;
  document.getElementById('pd-count').textContent=pc;
  document.getElementById('imp-count').textContent=ic;
}

// Shift+click range selection for checkbox groups.
// Only fires onSync on an actual range action; single clicks use their own onchange handlers.
const _rcLast={};
function enableRangeSelect(container,key,onSync){
  if(!container)return;
  container.addEventListener('click',e=>{
    const lbl=e.target.closest('label');
    if(!lbl)return;
    const cb=lbl.querySelector('input[type=checkbox]');
    if(!cb)return;
    const all=[...container.querySelectorAll('label:not(.family-hidden)')];
    const idx=all.indexOf(lbl);
    if(e.shiftKey&&_rcLast[key]!=null&&idx!==_rcLast[key]){
      const checked=cb.checked;
      const [lo,hi]=[Math.min(idx,_rcLast[key]),Math.max(idx,_rcLast[key])];
      all.slice(lo,hi+1).forEach(l=>{l.querySelector('input[type=checkbox]').checked=checked;});
      onSync();
    }
    _rcLast[key]=idx;
  });
}

// Init filters
(function(){
  // Family chips
  const bar=document.getElementById('family-bar');
  ALL_FAMILIES.forEach(fam=>{
    const lbl=document.createElement('label');
    lbl.className='family-cb';
    lbl.dataset.fam=fam;
    lbl.innerHTML='<input type="checkbox" checked onchange="toggleFamily(\''+fam.replace(/'/g,"\\'")+'\')"> '+fam;
    bar.appendChild(lbl);
  });
  // Model list
  const ALL_MODELS=[...new Set(DATA.map(d=>d.model))].sort();
  ALL_MODELS.forEach(m=>selModels.add(m));
  const ml=document.getElementById('model-list');
  ALL_MODELS.forEach(model=>{
    const safe=model.replace(/\\/g,'\\\\').replace(/'/g,"\\'");
    const lbl=document.createElement('label');
    lbl.className='model-cb';
    lbl.dataset.model=model;
    lbl.innerHTML='<input type="checkbox" checked onchange="toggleModel(\''+safe+'\')"> <span title="'+model.replace(/"/g,'&quot;')+'">'+displayName(model)+'</span>';
    ml.appendChild(lbl);
  });
  // Shift+click range select — lang and scorer re-read checkbox DOM state;
  // model list syncs selModels from DOM then renders.
  enableRangeSelect(document.getElementById('lang-checks'),'lang',()=>render());
  enableRangeSelect(document.getElementById('scorer-checks'),'scorer',()=>{
    ALL_SCORERS.forEach(sc=>{
      const el=document.getElementById('scorer_'+sc.replace(/[^a-zA-Z0-9]/g,'_'));
      if(el){if(el.checked)selScorers.add(sc);else selScorers.delete(sc);}
    });
    render();
  });
  enableRangeSelect(ml,'model',()=>{
    document.querySelectorAll('.model-cb').forEach(lbl=>{
      const m=lbl.dataset.model;const cb=lbl.querySelector('input');
      if(cb){if(cb.checked)selModels.add(m);else selModels.delete(m);}
    });
    render();
  });
})();

render();
$langDoughnutsJs
</script>
</body></html>"""
    }
}
