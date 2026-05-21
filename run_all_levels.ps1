# run_all_levels.ps1 -- 4 levels x 3 thuat toan = 12 simulations
# chi build 1 lan moi thuat toan

$ErrorActionPreference = "Continue"

$WORKSPACE      = "C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn"
$EXAMPLE_DIR    = "$WORKSPACE\example-sfc"
$RESULT_RAW     = "$EXAMPLE_DIR\result_fat-tree-wiki-workload.csv"
$CP_FILE        = "$WORKSPACE\cp.txt"
$SIMPLE_EXAMPLE = "$WORKSPACE\src\main\java\org\cloudbus\cloudsim\sdn\example\SimpleExample.java"

$NOS = [ordered]@{
    "pavs"       = "PAVScalingNOS"
    "worstfirst" = "WorstFirstNOS"
    "queuefirst" = "QueueFirstNOS"
}

$LEVELS = @("L1", "L2", "L3", "L4")

$M2Table = @{}
foreach ($lv in $LEVELS) { $M2Table[$lv] = @{} }

# ------------------------------------------------------------------
function Switch-NOS($nosClass) {
    $content = [System.IO.File]::ReadAllText($SIMPLE_EXAMPLE)
    $updated = $content -replace 'new (NoScaleNOS|PAVScalingNOS|WorstFirstNOS|QueueFirstNOS)\(\)', "new $nosClass()"
    [System.IO.File]::WriteAllText($SIMPLE_EXAMPLE, $updated)
    $ok = Select-String -Path $SIMPLE_EXAMPLE -Pattern "new $nosClass\(\)" -Quiet
    if ($ok) {
        Write-Host "  [*] Switched NOS -> $nosClass" -ForegroundColor Cyan
    } else {
        Write-Host "  [!] Switch FAILED" -ForegroundColor Red
        throw "Switch-NOS failed for $nosClass"
    }
}

function Build() {
    Write-Host "  [BUILD] mvn clean compile..." -ForegroundColor Yellow
    Push-Location $WORKSPACE
    mvn clean compile -q
    $ok = $LASTEXITCODE -eq 0
    Pop-Location
    if ($ok) {
        Write-Host "  [*] Build OK" -ForegroundColor Green
    } else {
        Write-Host "  [!] Build FAILED" -ForegroundColor Red
        throw "Build failed"
    }
}

function Set-Workload($level) {
    $src = "$EXAMPLE_DIR\fat-tree-wiki-workload-$level.csv"
    $dst = "$EXAMPLE_DIR\fat-tree-wiki-workload.csv"
    if (-not (Test-Path $src)) {
        Write-Host "  [!] Workload not found: $src" -ForegroundColor Red
        Write-Host "      Run: python gen_workloads.py" -ForegroundColor Red
        throw "Missing workload file for $level"
    }
    Copy-Item $src $dst -Force
    $lines = (Get-Content $src).Count - 1
    Write-Host "  [*] Workload set -> $level  ($lines data lines)" -ForegroundColor Cyan
}

function Run-Simulation($label, $level) {
    Write-Host "  [RUN] $label / $level ..." -ForegroundColor Yellow

    if (Test-Path $RESULT_RAW) { Remove-Item $RESULT_RAW -Force }

    $deps    = (Get-Content $CP_FILE -Raw).Trim()
    $CP      = "target\classes;$deps"
    $javaCmd = "java -cp `"$CP`" org.cloudbus.cloudsim.sdn.example.SimpleExample"
    $simLog  = "$WORKSPACE\simulation_log.txt"
    $logDest = "$WORKSPACE\simulation_log_${label}_${level}.txt"
    $csvDest = "$EXAMPLE_DIR\result_${label}_${level}.csv"

    Push-Location $WORKSPACE
    cmd /c "$javaCmd 2>nul"
    Pop-Location

    if (Test-Path $csvDest) { Remove-Item $csvDest -Force }
    if (Test-Path $RESULT_RAW) {
        Move-Item $RESULT_RAW $csvDest
        Write-Host "  [*] Result -> result_${label}_${level}.csv" -ForegroundColor Green
    } else {
        Write-Warning "  [!] result CSV not found"
        if (Test-Path $simLog) {
            Get-Content $simLog -Tail 5 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
        }
    }

    if (Test-Path $logDest) { Remove-Item $logDest -Force }
    if (Test-Path $simLog) {
        Copy-Item $simLog $logDest -Force
        $cis = Select-String -Path $simLog -Pattern "\[CIS\].*M2_sum" | Select-Object -Last 1
        if ($cis) {
            Write-Host "  $($cis.Line.Trim())" -ForegroundColor Magenta
        }
        Write-Host "  [*] Log  -> simulation_log_${label}_${level}.txt" -ForegroundColor Green
    }
}

function Get-M2FromLog($label, $level) {
    $logPath = "$WORKSPACE\simulation_log_${label}_${level}.txt"
    if (-not (Test-Path $logPath)) { return "N/A" }
    $cis = Select-String -Path $logPath -Pattern "\[CIS\].*M2_sum" | Select-Object -Last 1
    if ($cis -and $cis.Line -match "M2_sum=([\d.]+)") { return $Matches[1] }
    return "N/A"
}

function Show-ComparisonTable() {
    Write-Host ""
    Write-Host "==========================================================" -ForegroundColor White
    Write-Host "  Quick M2 Comparison  (cao hon = tot hon)" -ForegroundColor White
    Write-Host "==========================================================" -ForegroundColor White
    Write-Host ""
    Write-Host ("  {0,-10} {1,14} {2,14} {3,14}" -f "Level", "PAVS", "WorstFirst", "QueueFirst") -ForegroundColor White
    Write-Host ("  " + "-" * 56) -ForegroundColor DarkGray

    foreach ($lv in $LEVELS) {
        $m  = if ($M2Table[$lv].ContainsKey("pavs"))       { $M2Table[$lv]["pavs"]       } else { "N/A" }
        $w  = if ($M2Table[$lv].ContainsKey("worstfirst")) { $M2Table[$lv]["worstfirst"] } else { "N/A" }
        $q  = if ($M2Table[$lv].ContainsKey("queuefirst")) { $M2Table[$lv]["queuefirst"] } else { "N/A" }
        Write-Host ("  {0,-10} {1,14} {2,14} {3,14}" -f $lv, $m, $w, $q) -ForegroundColor Cyan
    }

    Write-Host ""
    # Find overall winner
    $bestLevel = $null; $bestAlgo = $null; $bestVal = -1.0
    foreach ($lv in $LEVELS) {
        foreach ($lbl in @("pavs","worstfirst","queuefirst")) {
            $raw = if ($M2Table[$lv].ContainsKey($lbl)) { $M2Table[$lv][$lbl] } else { "N/A" }
            try {
                $v = [double]$raw
                if ($v -gt $bestVal) { $bestVal = $v; $bestLevel = $lv; $bestAlgo = $lbl }
            } catch {}
        }
    }
    if ($bestAlgo) {
        Write-Host "  Peak M2: $bestAlgo @ $bestLevel = $bestVal" -ForegroundColor Green
    }
}

Write-Host "==========================================================" -ForegroundColor White
Write-Host "  PAVS Multi-Level Experiment Runner" -ForegroundColor White
Write-Host "  4 levels x 3 algorithms = 12 simulations" -ForegroundColor White
Write-Host "  TIME_OUT=90s | MONITOR_INTERVAL=30s" -ForegroundColor White
Write-Host "==========================================================" -ForegroundColor White

Set-Location $WORKSPACE

foreach ($lv in $LEVELS) {
    $wf = "$EXAMPLE_DIR\fat-tree-wiki-workload-$lv.csv"
    if (-not (Test-Path $wf)) {
        Write-Host "[!] Missing: $wf" -ForegroundColor Red
        Write-Host "    Run: python gen_workloads.py" -ForegroundColor Yellow
        exit 1
    }
}
Write-Host "[OK] All 4 workload files found." -ForegroundColor Green

if (-not (Test-Path $CP_FILE)) {
    Write-Host "[INIT] Building classpath..." -ForegroundColor Yellow
    mvn "dependency:build-classpath" "-Dmdep.outputFile=cp.txt" -q
}

$simNum = 0
$totalSims = $NOS.Count * $LEVELS.Count

foreach ($label in $NOS.Keys) {
    $nosClass = $NOS[$label]
    Write-Host ""
    Write-Host "==========================================================" -ForegroundColor White
    Write-Host "  Algorithm: $nosClass ($label)" -ForegroundColor White
    Write-Host "==========================================================" -ForegroundColor White

    Switch-NOS $nosClass
    $verify = Select-String -Path $SIMPLE_EXAMPLE -Pattern "new [A-Za-z]+NOS\(\)" | Select-Object -First 1
    Write-Host "  [verify] $($verify.Line.Trim())" -ForegroundColor DarkCyan
    Build

    foreach ($level in $LEVELS) {
        $simNum++
        Write-Host ""
        Write-Host "  --- [$simNum/$totalSims] $label / $level ---" -ForegroundColor White
        Set-Workload $level
        Run-Simulation $label $level
        $M2Table[$level][$label] = Get-M2FromLog $label $level
    }
}

Show-ComparisonTable

Write-Host ""
Write-Host "==========================================================" -ForegroundColor White
Write-Host "  [DONE] All $totalSims simulations complete!" -ForegroundColor Green
Write-Host "  Next: python generate_chart.py" -ForegroundColor White
Write-Host "==========================================================" -ForegroundColor White
