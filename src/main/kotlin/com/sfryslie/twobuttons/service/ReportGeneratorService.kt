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
        val languages = rows.map { it.lang }.distinct().sorted()
        val scorers = rows.firstOrNull()?.score?.scorers?.joinToString(", ") ?: ""
        val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(Instant.now())

        val disagreements = rows.filter { it.score.agreement == Agreement.DISAGREE }
        val ruleErrors = rows.filter { it.ruleError }

        // Per (model, lang) stats embedded as JS DATA array for client-side filtering
        data class MLStat(
            val model: String, val lang: String,
            val n: Int, val blue: Int, val red: Int, val none: Int,
            val ruleError: Int, val uds: Int, val adc: Int,
            val recants: Int, val disagree: Int, val agree: Int
        )
        val mlStats = rows.groupBy { it.model to it.lang }.map { (key, ms) ->
            MLStat(
                model = key.first, lang = key.second, n = ms.size,
                blue = ms.count { it.vote == "BLUE" },
                red  = ms.count { it.vote == "RED"  },
                none = ms.count { it.vote == "NONE" },
                ruleError = ms.count { it.ruleError },
                uds       = ms.count { it.uds },
                adc       = ms.count { it.adc },
                recants   = ms.count { it.recants },
                disagree  = ms.count { it.score.agreement == Agreement.DISAGREE },
                agree     = ms.count { it.score.agreement == Agreement.AGREE }
            )
        }

        val dataJs = mlStats.joinToString(",\n  ") { m ->
            val esc = m.model.replace("\"", "\\\"")
            "{model:\"$esc\",lang:\"${m.lang}\",n:${m.n},blue:${m.blue},red:${m.red},none:${m.none}," +
            "ruleError:${m.ruleError},uds:${m.uds},adc:${m.adc},recants:${m.recants},disagree:${m.disagree},agree:${m.agree}}"
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
                    if ("vote" in cols) append("<td class='c-${out.vote.name.lowercase()}'>${out.vote}</td>")
                    if ("conf" in cols) append("<td>${out.confidence}</td>")
                    if ("rule" in cols) append("<td>${if (out.ruleError) "⚠ yes" else "no"}</td>")
                    append("<td class='rt'>${out.reasoning.replace("<", "&lt;")}</td></tr>")
                }
            }

        val disRows = disagreements.joinToString("\n") { s ->
            """<details class="card-item" data-lang="${s.lang}">
<summary><span class="tag-model">${s.model}</span><span class="tag-lang">${s.lang}</span><span class="tag-file">${s.file.removeSuffix(".score.json").takeLast(40)}</span></summary>
<table class="it"><thead><tr><th>Scorer</th><th>Vote</th><th>Confidence</th><th>Rule Error</th><th>Reasoning</th></tr></thead>
<tbody>${scorerRowsFor(s, listOf("vote","conf","rule"))}</tbody></table></details>"""
        }

        val errRows = ruleErrors.joinToString("\n") { s ->
            val scorerHtml = s.score.scores.entries.filter { it.value?.ruleError == true }
                .joinToString("") { (scorer, out) ->
                    if (out == null) "" else
                    "<tr class='sr'><td class='sn'>$scorer</td>" +
                    "<td class='c-${out.vote.name.lowercase()}'>${out.vote}</td>" +
                    "<td class='rt'>${out.reasoning.replace("<", "&lt;")}</td></tr>"
                }
            """<details class="card-item" data-lang="${s.lang}">
<summary><span class="tag-model">${s.model}</span><span class="tag-lang">${s.lang}</span><span class="tag-file">${s.file.removeSuffix(".score.json").takeLast(40)}</span></summary>
<table class="it"><thead><tr><th>Scorer</th><th>Vote</th><th>Reasoning</th></tr></thead>
<tbody>$scorerHtml</tbody></table></details>"""
        }

        val langCheckboxes = languages.joinToString(" ") { lang ->
            """<label class="lang-cb"><input type="checkbox" id="lang_$lang" value="$lang" checked onchange="render()"> $lang</label>"""
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
main{padding:24px 32px;max-width:1400px}
section{background:#fff;border-radius:8px;padding:20px 24px;margin-bottom:20px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
section h2{font-size:14px;font-weight:600;text-transform:uppercase;letter-spacing:.5px;color:#555;margin-bottom:16px;border-bottom:1px solid #eee;padding-bottom:10px}
.filter-bar{display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap}
.filter-bar .lbl{font-size:12px;font-weight:600;color:#777;text-transform:uppercase;letter-spacing:.5px}
.lang-cb{display:flex;align-items:center;gap:5px;font-size:13px;cursor:pointer;background:#f0f0ff;border-radius:4px;padding:4px 10px;border:1px solid #e0e0f0}
.lang-cb input{cursor:pointer}
.chart-wrap{position:relative;height:${chartHeight}px}
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
</style></head>
<body>
<header>
  <h1>Two Buttons — Scoring Report</h1>
  <div class="sub">Generated $ts &nbsp;·&nbsp; Scorers: $scorers &nbsp;·&nbsp; Languages: ${languages.joinToString(", ")}</div>
  <div class="stats">
    <div class="stat hi"><div class="val" id="stat-total">$total</div><div class="lbl">Sessions</div></div>
    <div class="stat"><div class="val" id="stat-blue"></div><div class="lbl">Voted Blue</div></div>
    <div class="stat"><div class="val" id="stat-red"></div><div class="lbl">Voted Red</div></div>
    <div class="stat"><div class="val" id="stat-agree"></div><div class="lbl">Scorer Agreement</div></div>
    <div class="stat"><div class="val" id="stat-disagree"></div><div class="lbl">Disagreements</div></div>
    <div class="stat"><div class="val" id="stat-rule"></div><div class="lbl">Rule Errors</div></div>
    <div class="stat"><div class="val" id="stat-recants"></div><div class="lbl">Recants by Q4</div></div>
  </div>
</header>
<main>

<section>
  <h2>Vote Distribution by Model</h2>
  <div class="filter-bar">
    <span class="lbl">Language</span>
    $langCheckboxes
  </div>
  <div class="chart-wrap"><canvas id="voteChart"></canvas></div>
</section>

<section>
  <h2>Model Summary</h2>
  <table>
    <thead><tr><th>Model</th><th>N</th><th>Blue%</th><th>Red%</th><th>None%</th><th>Rule Error%</th><th>Understands Dom%</th><th>Applies Dom%</th><th>Recants%</th><th>Disagree%</th></tr></thead>
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

</main>
<script>
const BLUE='#3b82f6',RED='#ef4444',NONE='#9ca3af';
const LANGS=[$langsJs];
const DATA=[
  $dataJs];

function getSelectedLangs(){return LANGS.filter(l=>{const el=document.getElementById('lang_'+l);return el?el.checked:true;});}
function pct(a,b){return b>0?Math.round(a*100/b):0;}
function fmt(n,t){return n+' <span style="font-size:16px;color:#8888aa">('+pct(n,t)+'%)</span>';}

function aggregate(langs){
  const filtered=DATA.filter(d=>langs.includes(d.lang));
  const byModel={};
  for(const d of filtered){
    if(!byModel[d.model])byModel[d.model]={model:d.model,n:0,blue:0,red:0,none:0,ruleError:0,uds:0,adc:0,recants:0,disagree:0,agree:0};
    const m=byModel[d.model];
    m.n+=d.n;m.blue+=d.blue;m.red+=d.red;m.none+=d.none;
    m.ruleError+=d.ruleError;m.uds+=d.uds;m.adc+=d.adc;m.recants+=d.recants;m.disagree+=d.disagree;m.agree+=d.agree;
  }
  return Object.values(byModel).sort((a,b)=>pct(b.blue,b.n)-pct(a.blue,a.n));
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
    scales:{x:{stacked:true,grid:{color:'#f0f0f0'}},y:{stacked:true,ticks:{font:{size:11}}}},
    plugins:{legend:{position:'top'}}
  }
});

function render(){
  const langs=getSelectedLangs();
  const models=aggregate(langs);
  const total=models.reduce((s,m)=>s+m.n,0);
  const tBlue=models.reduce((s,m)=>s+m.blue,0);
  const tRed=models.reduce((s,m)=>s+m.red,0);
  const tAgree=models.reduce((s,m)=>s+m.agree,0);
  const tDis=models.reduce((s,m)=>s+m.disagree,0);
  const tRule=models.reduce((s,m)=>s+m.ruleError,0);
  const tRec=models.reduce((s,m)=>s+m.recants,0);

  document.getElementById('stat-total').textContent=total;
  document.getElementById('stat-blue').innerHTML=fmt(tBlue,total);
  document.getElementById('stat-red').innerHTML=fmt(tRed,total);
  document.getElementById('stat-agree').textContent=pct(tAgree,total)+'%';
  document.getElementById('stat-disagree').textContent=tDis;
  document.getElementById('stat-rule').innerHTML=fmt(tRule,total);
  document.getElementById('stat-recants').innerHTML=fmt(tRec,total);

  voteChart.data.labels=models.map(m=>m.model);
  voteChart.data.datasets[0].data=models.map(m=>m.blue);
  voteChart.data.datasets[1].data=models.map(m=>m.red);
  voteChart.data.datasets[2].data=models.map(m=>m.none);
  voteChart.update();

  document.getElementById('modelTbody').innerHTML=models.map(m=>
    '<tr><td>'+m.model+'</td><td>'+m.n+'</td>'+
    '<td class="c-blue">'+pct(m.blue,m.n)+'%</td><td class="c-red">'+pct(m.red,m.n)+'%</td><td class="c-none">'+pct(m.none,m.n)+'%</td>'+
    '<td>'+pct(m.ruleError,m.n)+'%</td><td>'+pct(m.uds,m.n)+'%</td><td>'+pct(m.adc,m.n)+'%</td><td>'+pct(m.recants,m.n)+'%</td><td>'+pct(m.disagree,m.n)+'%</td></tr>'
  ).join('');

  document.querySelectorAll('.card-item[data-lang]').forEach(el=>{
    el.style.display=langs.includes(el.dataset.lang)?'':'none';
  });
  let dc=0,ec=0;
  document.querySelectorAll('#dis-container .card-item[data-lang]').forEach(el=>{if(el.style.display!=='none')dc++;});
  document.querySelectorAll('#err-container .card-item[data-lang]').forEach(el=>{if(el.style.display!=='none')ec++;});
  document.getElementById('dis-count').textContent=dc;
  document.getElementById('err-count').textContent=ec;
}

render();
$langDoughnutsJs
</script>
</body></html>"""
    }
}
