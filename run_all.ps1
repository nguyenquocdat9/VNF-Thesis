# run_all.ps1 -- Chay lan luot 3 thuat toan va so sanh ket qua
# MSH-OR | WorstFirst | QueueFirst  (tat ca chi dung Vertical Scale)

$ErrorActionPreference = "Continue"

$WORKSPACE      = "C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn"
$EXAMPLE_DIR    = "$WORKSPACE\example-sfc"
$RESULT_RAW     = "$EXAMPLE_DIR\result_fat-tree-wiki-workload.csv"
$CP_FILE        = "$WORKSPACE\cp.txt"
$SIMPLE_EXAMPLE = "$WORKSPACE\src\main\java\org\cloudbus\cloudsim\sdn\example\SimpleExample.java"

$NOS = [ordered]@{
    "mshor"      = "MshOrNOS"
    "worstfirst" = "WorstFirstNOS"
    "queuefirst" = "QueueFirstNOS"
}

function Switch-NOS($nosClass) {
    $content = [System.IO.File]::ReadAllText($SIMPLE_EXAMPLE)
    $updated = $content -replace 'new (MshOrNOS|WorstFirstNOS|QueueFirstNOS)\(\)', "new $nosClass()"
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
    Write-Host "[BUILD] mvn clean compile..." -ForegroundColor Yellow
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

function Run-Simulation($label) {
    Write-Host "[RUN] $label ..." -ForegroundColor Yellow

    if (Test-Path $RESULT_RAW) { Remove-Item $RESULT_RAW -Force }

    $deps    = (Get-Content $CP_FILE -Raw).Trim()
    $CP      = "target\classes;$deps"
    $javaCmd = "java -cp `"$CP`" org.cloudbus.cloudsim.sdn.example.SimpleExample"
    $simLog  = "$WORKSPACE\simulation_log.txt"
    $logDest = "$WORKSPACE\simulation_log_$label.txt"
    $csvDest = "$EXAMPLE_DIR\result_$label.csv"

    Push-Location $WORKSPACE
    cmd /c "$javaCmd 2>nul"
    Pop-Location

    Write-Host "  [*] Simulation done" -ForegroundColor Green

    # Luu result CSV
    if (Test-Path $csvDest) { Remove-Item $csvDest }
    if (Test-Path $RESULT_RAW) {
        Move-Item $RESULT_RAW $csvDest
        Write-Host "  [*] Result -> result_$label.csv" -ForegroundColor Green
    } else {
        Write-Warning "  [!] result CSV not found"
        if (Test-Path $simLog) { Get-Content $simLog -Tail 5 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red } }
    }

    # In WQB va CIS
    if (Test-Path $simLog) {
        Select-String -Path $simLog -Pattern "\[WQB\]|\[CIS\]" | ForEach-Object {
            Write-Host "  $($_.Line.Trim())" -ForegroundColor Magenta
        }
        # XOA file log cu truoc khi copy -- tranh log cu gay parse sai
        if (Test-Path $logDest) { Remove-Item $logDest -Force }
        Copy-Item $simLog $logDest -Force
        Write-Host "  [*] Log  -> simulation_log_$label.txt" -ForegroundColor Green
    }
}

function Show-QuickComparison() {
    Write-Host ""
    Write-Host "======================================" -ForegroundColor White
    Write-Host " Quick comparison (tu log)" -ForegroundColor White
    Write-Host "======================================" -ForegroundColor White

    $labels = @("mshor", "worstfirst", "queuefirst")
    $names  = @("MSH-OR    ", "WorstFirst", "QueueFirst")

    $results = @{}
    for ($i = 0; $i -lt $labels.Count; $i++) {
        $label = $labels[$i]
        $log   = "$WORKSPACE\simulation_log_$label.txt"
        $r     = @{ name=$names[$i]; m1="N/A"; m2="N/A"; m3="N/A"; wqb="N/A" }

        if (Test-Path $log) {
            $cis = Select-String -Path $log -Pattern "\[CIS\].*M1_sum" | Select-Object -Last 1
            if ($cis) {
                if ($cis.Line -match "M1_sum=([\d.]+)") { $r.m1 = $Matches[1] }
                if ($cis.Line -match "M2_sum=([\d.]+)") { $r.m2 = $Matches[1] }
                if ($cis.Line -match "M3_sum=([\d.]+)") { $r.m3 = $Matches[1] }
            }
            $wqb = Select-String -Path $log -Pattern "\[WQB\].*=" | Select-Object -Last 1
            if ($wqb) {
                if ($wqb.Line -match "=\s*([\d.]+)") { $r.wqb = $Matches[1] }
            }
        }
        $results[$label] = $r
    }

    Write-Host ""
    Write-Host ("  {0,-12} {1,10} {2,12} {3,10} {4,14}" -f "Algorithm", "M1 (DC1)", "M2 (DC2) KEY", "M3 (DC3)", "WQB") -ForegroundColor White
    Write-Host ("  " + "-"*62) -ForegroundColor DarkGray

    foreach ($label in $labels) {
        $r = $results[$label]
        Write-Host ("  {0,-12} {1,10} {2,12} {3,10} {4,14}" -f $r.name, $r.m1, $r.m2, $r.m3, $r.wqb) -ForegroundColor Cyan
    }

    Write-Host ""
    Write-Host "  M2 cao hon = tot hon (priority-weighted SFC violations giam duoc)" -ForegroundColor DarkGray
    Write-Host "  WQB thap hon = tot hon (Weighted Queue Burden)" -ForegroundColor DarkGray

    $best = $null; $bestVal = -1
    foreach ($label in $labels) {
        $raw = $results[$label].m2
        try {
            $val = [double]$raw
            if ($val -gt $bestVal) { $bestVal = $val; $best = $results[$label].name.Trim() }
        } catch {}
    }
    if ($best) {
        Write-Host ""
        Write-Host "  Winner M2: $best" -ForegroundColor Green
    }
}

# =========================================================
# MAIN
# =========================================================

Write-Host "======================================" -ForegroundColor White
Write-Host " MSH-OR Experiment Runner" -ForegroundColor White
Write-Host " 3 thuat toan: MSH-OR | WorstFirst | QueueFirst" -ForegroundColor White
Write-Host " Tat ca chi dung Vertical Scale (M/M/1)" -ForegroundColor White
Write-Host " TIME_OUT = 90s | MONITOR_INTERVAL = 30s" -ForegroundColor White
Write-Host "======================================" -ForegroundColor White

Set-Location $WORKSPACE

if (-not (Test-Path $CP_FILE)) {
    Write-Host "[INIT] Building classpath..." -ForegroundColor Yellow
    mvn "dependency:build-classpath" "-Dmdep.outputFile=cp.txt" -q
}

foreach ($label in $NOS.Keys) {
    $nosClass = $NOS[$label]
    Write-Host ""
    Write-Host "======================================" -ForegroundColor White
    Write-Host " $nosClass ($label)" -ForegroundColor White
    Write-Host "======================================" -ForegroundColor White

    Switch-NOS $nosClass

    $line = Select-String -Path $SIMPLE_EXAMPLE -Pattern "new [A-Za-z]+NOS\(\)" | Select-Object -First 1
    Write-Host "  [verify] $($line.Line.Trim())" -ForegroundColor DarkCyan

    Build
    Run-Simulation $label
}

Show-QuickComparison

Write-Host ""
Write-Host "======================================" -ForegroundColor White
Write-Host " Parsing results (chi tiet)..." -ForegroundColor White
Write-Host "======================================" -ForegroundColor White
python "$WORKSPACE\parse_results.py"

Write-Host ""
Write-Host "[DONE] Xong!" -ForegroundColor Green