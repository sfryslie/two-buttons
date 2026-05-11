package com.sfryslie.twobuttons.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sfryslie.twobuttons.config.ScoringProperties
import com.sfryslie.twobuttons.model.Agreement
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
        val recants: Boolean,
        val safetyRefusal: Boolean
    )

    fun generateReport() {
        val outDir = Paths.get(properties.outputDir)
        if (!Files.exists(outDir)) return

        val rows = mutableListOf<Row>()
        Files.list(outDir).filter { Files.isDirectory(it) }.sorted().forEach { langDir ->
            val lang = langDir.fileName.toString()
            Files.list(langDir).filter { Files.isDirectory(it) }.sorted().forEach { modelDir ->
                val model = modelDir.fileName.toString()
                Files.list(modelDir).filter { it.fileName.toString().endsWith(".score.json") }.forEach { f ->
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
            vote = score.majorityVote.name,
            confidence = if (s.isEmpty()) "UNKNOWN" else maj { it.confidence }.name,
            ruleError = bool { it.ruleError },
            uds = bool { it.understandsDominantStrategy },
            adc = bool { it.appliesDominanceCorrectly },
            recants = bool { it.recantsBy_q4 },
            safetyRefusal = bool { it.safetyRefusal }
        )
    }

    private fun buildHtml(rows: List<Row>): String {
        val total = rows.size
        val agreePct = rows.count { it.score.agreement == Agreement.AGREE } * 100 / total
        val disagreements = rows.filter { it.score.agreement == Agreement.DISAGREE }
        val ruleErrors = rows.filter { it.ruleError }
        val languages = rows.map { it.lang }.distinct().sorted()
        val scorers = rows.firstOrNull()?.score?.scorers?.joinToString(", ") ?: ""
        val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(Instant.now())

        // per-model stats sorted by blue%
        val modelStats = rows.groupBy { it.model }.entries.map { (model, ms) ->
            val n = ms.size
            fun pct(pred: (Row) -> Boolean) = if (n > 0) ms.count(pred) * 100 / n else 0
            mapOf("model" to model, "n" to n,
                "blue" to ms.count { it.vote == "BLUE" }, "red" to ms.count { it.vote == "RED" }, "none" to ms.count { it.vote == "NONE" },
                "bluePct" to pct { it.vote == "BLUE" }, "redPct" to pct { it.vote == "RED" }, "nonePct" to pct { it.vote == "NONE" },
                "rulePct" to pct { it.ruleError }, "udsPct" to pct { it.uds }, "adcPct" to pct { it.adc },
                "recantsPct" to pct { it.recants }, "disPct" to pct { it.score.agreement == Agreement.DISAGREE })
        }.sortedByDescending { it["bluePct"] as Int }

        // per-language stats
        val langStats = rows.groupBy { it.lang }.entries.map { (lang, ls) ->
            val n = ls.size
            mapOf("lang" to lang, "n" to n,
                "blue" to ls.count { it.vote == "BLUE" }, "red" to ls.count { it.vote == "RED" }, "none" to ls.count { it.vote == "NONE" })
        }.sortedBy { it["lang"] as String }

        val modelLabels    = modelStats.joinToString(",") { "\"${it["model"]}\"" }
        val modelBlue      = modelStats.joinToString(",") { "${it["blue"]}" }
        val modelRed       = modelStats.joinToString(",") { "${it["red"]}" }
        val modelNone      = modelStats.joinToString(",") { "${it["none"]}" }
        val langLabels     = langStats.joinToString(",") { "\"${it["lang"]}\"" }
        val langBlue       = langStats.joinToString(",") { "${it["blue"]}" }
        val langRed        = langStats.joinToString(",") { "${it["red"]}" }
        val langNone       = langStats.joinToString(",") { "${it["none"]}" }

        val modelRows = modelStats.joinToString("\n") { m ->
            "<tr><td>${m["model"]}</td><td>${m["n"]}</td>" +
            "<td class='c-blue'>${m["bluePct"]}%</td><td class='c-red'>${m["redPct"]}%</td><td class='c-none'>${m["nonePct"]}%</td>" +
            "<td>${m["rulePct"]}%</td><td>${m["udsPct"]}%</td><td>${m["adcPct"]}%</td><td>${m["recantsPct"]}%</td><td>${m["disPct"]}%</td></tr>"
        }

        fun scorerRowsFor(s: Row, cols: List<String>): String =
            s.score.scores.entries.joinToString("") { (scorer, out) ->
                if (out == null) "" else buildString {
                    append("<tr class='sr'>")
                    append("<td class='sn'>$scorer</td>")
                    if ("vote" in cols) append("<td class='c-${out.vote.name.lowercase()}'>${out.vote}</td>")
                    if ("conf" in cols) append("<td>${out.confidence}</td>")
                    if ("rule" in cols) append("<td>${if (out.ruleError) "⚠ yes" else "no"}</td>")
                    append("<td class='rt'>${out.reasoning.take(240).replace("<","&lt;")}</td>")
                    append("</tr>")
                }
            }

        val disRows = disagreements.joinToString("\n") { s ->
            """<details class="card-item">
<summary><span class="tag-model">${s.model}</span><span class="tag-lang">${s.lang}</span><span class="tag-file">${s.file.removeSuffix(".score.json").takeLast(40)}</span></summary>
<table class="it"><thead><tr><th>Scorer</th><th>Vote</th><th>Confidence</th><th>Rule Error</th><th>Reasoning</th></tr></thead>
<tbody>${scorerRowsFor(s, listOf("vote","conf","rule"))}</tbody></table>
</details>"""
        }

        val errRows = ruleErrors.joinToString("\n") { s ->
            val scorerHtml = s.score.scores.entries.filter { it.value?.ruleError == true }
                .joinToString("") { (scorer, out) ->
                    if (out == null) "" else
                    "<tr class='sr'><td class='sn'>$scorer</td><td class='c-${out.vote.name.lowercase()}'>${out.vote}</td>" +
                    "<td class='rt'>${out.reasoning.take(320).replace("<","&lt;")}</td></tr>"
                }
            """<details class="card-item">
<summary><span class="tag-model">${s.model}</span><span class="tag-lang">${s.lang}</span><span class="tag-file">${s.file.removeSuffix(".score.json").takeLast(40)}</span></summary>
<table class="it"><thead><tr><th>Scorer</th><th>Vote</th><th>Reasoning</th></tr></thead>
<tbody>$scorerHtml</tbody></table>
</details>"""
        }

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
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
main{padding:24px 32px;max-width:1400px}
section{background:#fff;border-radius:8px;padding:20px 24px;margin-bottom:20px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
section h2{font-size:14px;font-weight:600;text-transform:uppercase;letter-spacing:.5px;color:#555;margin-bottom:16px;border-bottom:1px solid #eee;padding-bottom:10px}
.chart-wrap{position:relative;height:${(modelStats.size * 28 + 60).coerceAtLeast(200)}px}
table{width:100%;border-collapse:collapse;font-size:13px}
th{text-align:left;padding:8px 10px;background:#f8f9fa;border-bottom:2px solid #e0e0e0;font-weight:600;color:#555;white-space:nowrap}
td{padding:7px 10px;border-bottom:1px solid #f0f0f0}
tr:last-child td{border-bottom:none}
tr:hover td{background:#fafafa}
.c-blue{color:#2563eb;font-weight:600}
.c-red{color:#dc2626;font-weight:600}
.c-none{color:#666}
details.card-item{border:1px solid #e8e8e8;border-radius:6px;margin-bottom:8px;overflow:hidden}
details.card-item summary{padding:10px 14px;cursor:pointer;background:#fafafa;display:flex;align-items:center;gap:8px;user-select:none}
details.card-item summary:hover{background:#f0f0f0}
details.card-item[open] summary{background:#f0f0ff;border-bottom:1px solid #e0e0f0}
.tag-model{font-weight:600;font-size:13px}
.tag-lang{background:#e0e7ff;color:#3730a3;border-radius:4px;padding:2px 7px;font-size:11px;font-weight:600}
.tag-file{color:#888;font-size:11px;font-family:monospace;margin-left:4px}
table.it{margin:12px;width:calc(100% - 24px);font-size:12px}
.sr td{vertical-align:top}
.sn{font-weight:600;color:#444;white-space:nowrap}
.rt{color:#444;line-height:1.5;max-width:600px}
.lang-charts{display:flex;gap:20px;flex-wrap:wrap}
.lang-chart-box{flex:1;min-width:180px;max-width:260px}
.lang-chart-box h3{font-size:12px;font-weight:600;color:#555;text-align:center;margin-bottom:8px}
.empty{color:#aaa;font-style:italic;padding:12px 0}
</style>
</head>
<body>
<header>
  <h1>Two Buttons — Scoring Report</h1>
  <div class="sub">Generated $ts &nbsp;·&nbsp; Scorers: $scorers &nbsp;·&nbsp; Languages: ${languages.joinToString(", ")}</div>
  <div class="stats">
    <div class="stat hi"><div class="val">$total</div><div class="lbl">Sessions</div></div>
    <div class="stat"><div class="val">${rows.count{it.vote=="BLUE"}} <span style="font-size:16px;color:#8888aa">(${rows.count{it.vote=="BLUE"}*100/total}%)</span></div><div class="lbl">Voted Blue</div></div>
    <div class="stat"><div class="val">${rows.count{it.vote=="RED"}} <span style="font-size:16px;color:#8888aa">(${rows.count{it.vote=="RED"}*100/total}%)</span></div><div class="lbl">Voted Red</div></div>
    <div class="stat"><div class="val">$agreePct%</div><div class="lbl">Scorer Agreement</div></div>
    <div class="stat"><div class="val">${disagreements.size}</div><div class="lbl">Disagreements</div></div>
    <div class="stat"><div class="val">${ruleErrors.size} <span style="font-size:16px;color:#8888aa">(${ruleErrors.size*100/total}%)</span></div><div class="lbl">Rule Errors</div></div>
    <div class="stat"><div class="val">${rows.count{it.recants}} <span style="font-size:16px;color:#8888aa">(${rows.count{it.recants}*100/total}%)</span></div><div class="lbl">Recants by Q4</div></div>
  </div>
</header>
<main>

<section>
  <h2>Vote Distribution by Model</h2>
  <div class="chart-wrap"><canvas id="voteChart"></canvas></div>
</section>

<section>
  <h2>Model Summary</h2>
  <table>
    <thead><tr><th>Model</th><th>N</th><th>Blue%</th><th>Red%</th><th>None%</th><th>Rule Error%</th><th>Understands Dom%</th><th>Applies Dom%</th><th>Recants%</th><th>Disagree%</th></tr></thead>
    <tbody>$modelRows</tbody>
  </table>
</section>

${if (languages.size > 1) """<section>
  <h2>Language Comparison</h2>
  <div class="lang-charts">
${langStats.joinToString("\n") { ls ->
    val n = ls["n"] as Int
    val b = ls["blue"] as Int
    val r = ls["red"] as Int
    val no = ls["none"] as Int
    """    <div class="lang-chart-box">
      <h3>${ls["lang"]} (n=$n)</h3>
      <canvas id="langChart_${ls["lang"]}"></canvas>
    </div>"""
}}
  </div>
</section>""" else ""}

<section>
  <h2>Scorer Disagreements (${disagreements.size})</h2>
  ${if (disagreements.isEmpty()) "<p class='empty'>No disagreements.</p>" else disRows}
</section>

<section>
  <h2>Rule Errors (${ruleErrors.size})</h2>
  ${if (ruleErrors.isEmpty()) "<p class='empty'>No rule errors detected.</p>" else errRows}
</section>

</main>
<script>
const BLUE = '#3b82f6', RED = '#ef4444', NONE = '#9ca3af';

new Chart(document.getElementById('voteChart'), {
  type: 'bar',
  data: {
    labels: [$modelLabels],
    datasets: [
      { label: 'BLUE', data: [$modelBlue], backgroundColor: BLUE },
      { label: 'RED',  data: [$modelRed],  backgroundColor: RED  },
      { label: 'NONE', data: [$modelNone], backgroundColor: NONE }
    ]
  },
  options: {
    indexAxis: 'y',
    responsive: true, maintainAspectRatio: false,
    scales: {
      x: { stacked: true, grid: { color: '#f0f0f0' } },
      y: { stacked: true, ticks: { font: { size: 11 } } }
    },
    plugins: { legend: { position: 'top' } }
  }
});

${if (languages.size > 1) langStats.joinToString("\n") { ls ->
    val lang = ls["lang"]
    val b = ls["blue"]; val r = ls["red"]; val n = ls["none"]
    """new Chart(document.getElementById('langChart_$lang'), {
  type: 'doughnut',
  data: { labels: ['BLUE','RED','NONE'], datasets: [{ data: [$b,$r,$n], backgroundColor: [BLUE,RED,NONE], borderWidth: 1 }] },
  options: { plugins: { legend: { position: 'bottom', labels: { font: { size: 11 } } } } }
});"""
} else ""}
</script>
</body>
</html>"""
    }
}
