# run_seeds.ps1 -- Chay Random voi nhieu seed de so sanh WQB variance

$ErrorActionPreference = "Continue"

$WORKSPACE   = "C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn"
$EXAMPLE_DIR = "$WORKSPACE\example-sfc"
$RESULT_RAW  = "$EXAMPLE_DIR\result_fat-tree-wiki-workload.csv"
$CP_FILE     = "$WORKSPACE\cp.txt"
$RANDOM_NOS  = "$WORKSPACE\src\main\java\org\cloudbus\cloudsim\sdn\nos\RandomScaleNOS.java"
$SIMPLE_EX   = "$WORKSPACE\src\main\java\org\cloudbus\cloudsim\sdn\example\SimpleExample.java"

# Seeds can test
$SEEDS = @(1, 7, 42, 99, 123, 256, 999)

function Build() {
    Push-Location $WORKSPACE
    mvn clean compile -q
    Pop-Location
}

function Run-Sim($label) {
    if (Test-Path $RESULT_RAW) { Remove-Item $RESULT_RAW -Force }
    $deps = (Get-Content $CP_FILE -Raw).Trim()
    $CP   = "target\classes;$deps"
    Push-Location $WORKSPACE
    cmd /c "java -cp `"$CP`" org.cloudbus.cloudsim.sdn.example.SimpleExample 2>nul"
    Pop-Location

    $dest = "$EXAMPLE_DIR\result_$label.csv"
    if (Test-Path $dest) { Remove-Item $dest -Force }
    if (Test-Path $RESULT_RAW) { Move-Item $RESULT_RAW $dest }

    $simLog = "$WORKSPACE\simulation_log.txt"
    $logDest = "$WORKSPACE\simulation_log_$label.txt"
    if (Test-Path $simLog) { Copy-Item $simLog $logDest -Force }

    # Extract WQB
    $wqb = Select-String -Path $simLog -Pattern "\[WQB\]" | Select-Object -First 1
    if ($wqb) { return $wqb.Line.Split("=")[-1].Trim() }
    return "N/A"
}

Set-Location $WORKSPACE

# Switch SimpleExample to RandomScaleNOS
$content = [System.IO.File]::ReadAllText($SIMPLE_EX)
$content = $content -replace 'new (MshOrNOS|RandomScaleNOS|FirstFitNOS)\(\)', 'new RandomScaleNOS()'
[System.IO.File]::WriteAllText($SIMPLE_EX, $content)

Write-Host "======================================" -ForegroundColor White
Write-Host " Random Seed Variance Test" -ForegroundColor White
Write-Host "======================================" -ForegroundColor White
Write-Host ""

$results = @()

foreach ($seed in $SEEDS) {
    Write-Host "--- Seed=$seed ---" -ForegroundColor Cyan

    # Change seed in RandomScaleNOS.java
    $rcode = [System.IO.File]::ReadAllText($RANDOM_NOS)
    $rcode = $rcode -replace 'new Random\(\d+\)', "new Random($seed)"
    [System.IO.File]::WriteAllText($RANDOM_NOS, $rcode)

    Build
    $wqb = Run-Sim "random_seed$seed"
    Write-Host "  WQB = $wqb" -ForegroundColor Magenta

    $results += [PSCustomObject]@{ Seed=$seed; WQB=$wqb }
}

# Reset seed to 42
$rcode = [System.IO.File]::ReadAllText($RANDOM_NOS)
$rcode = $rcode -replace 'new Random\(\d+\)', "new Random(42)"
[System.IO.File]::WriteAllText($RANDOM_NOS, $rcode)

Write-Host ""
Write-Host "======================================" -ForegroundColor White
Write-Host " Results Summary" -ForegroundColor White
Write-Host "======================================" -ForegroundColor White
Write-Host "  MSH-OR WQB (reference): 217901.6" -ForegroundColor Green
Write-Host ""
foreach ($r in $results) {
    $diff = ""
    try {
        $v = [double]$r.WQB
        $d = $v - 217901.6
        $sign = if ($d -gt 0) { "+" } else { "" }
        $diff = "  (vs MSH-OR: $sign$([math]::Round($d,1)))"
    } catch {}
    Write-Host "  Seed=$($r.Seed): WQB=$($r.WQB)$diff"
}