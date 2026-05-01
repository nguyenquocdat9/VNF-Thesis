# run_all.ps1 v3 -- don gian, dung cmd /c de tranh PS NativeCommandError

$ErrorActionPreference = "Continue"

$WORKSPACE   = "C:\Users\Admin\Documents\GitHub\cloudsim-workspace\cloudsimsdn"
$EXAMPLE_DIR = "$WORKSPACE\example-sfc"
$RESULT_RAW  = "$EXAMPLE_DIR\result_fat-tree-wiki-workload.csv"
$CP_FILE     = "$WORKSPACE\cp.txt"

$SIMPLE_EXAMPLE = "$WORKSPACE\src\main\java\org\cloudbus\cloudsim\sdn\example\SimpleExample.java"

$NOS = @{
    "mshor"    = "MshOrNOS"
    "random"   = "WorstFirstNOS"
    "firstfit" = "QueueFirstNOS"
}

$NOS_PATTERN = 'new (MshOrNOS|RandomScaleNOS|FirstFitNOS|WorstFirstNOS)\('

function Switch-NOS($nosClass) {
    $file = $SIMPLE_EXAMPLE
    $content = [System.IO.File]::ReadAllText($file)
    $updated = $content -replace 'new (MshOrNOS|RandomScaleNOS|FirstFitNOS|WorstFirstNOS|QueueFirstNOS)\(\)', "new $nosClass()"
    [System.IO.File]::WriteAllText($file, $updated)
    # Verify
    $check = Select-String -Path $file -Pattern "new $nosClass\(\)" -Quiet
    if ($check) {
        Write-Host "  [*] Switched NOS -> $nosClass" -ForegroundColor Cyan
    } else {
        Write-Host "  [!] Switch FAILED -- kiem tra tay SimpleExample.java dong 112" -ForegroundColor Red
        throw "Switch-NOS failed for $nosClass"
    }
}

function Build() {
    Write-Host "[BUILD] mvn clean compile..." -ForegroundColor Yellow
    Push-Location $WORKSPACE
    mvn clean compile -q
    Pop-Location
    Write-Host "  [*] Build OK" -ForegroundColor Green
}

function Run-Simulation($label) {
    Write-Host "[RUN] $label ..." -ForegroundColor Yellow

    if (Test-Path $RESULT_RAW) { Remove-Item $RESULT_RAW -Force }

    $logFile = "$WORKSPACE\simulation_log_$label.txt"
    # simulation_log.txt se bi overwrite boi Java, khong can xoa truoc

    $deps = (Get-Content $CP_FILE -Raw).Trim()
    $CP   = "target\classes;$deps"

    # Dung cmd /c de chay java -- tranh PS bat stderr lam exception
    # Output ghi thang vao file log, dong thoi hien thi len console
    $javaCmd = "java -cp `"$CP`" org.cloudbus.cloudsim.sdn.example.SimpleExample"

    Push-Location $WORKSPACE
    # Java tu redirect System.out vao simulation_log.txt (SimpleExample.java dong 74-76)
    # Chi can chay java, stderr (warnings) bo qua
    cmd /c "$javaCmd 2>nul"
    Pop-Location

    # simulation_log.txt duoc Java ghi vao $WORKSPACE
    $simLog = "$WORKSPACE\simulation_log.txt"

    Write-Host "  [*] Simulation done" -ForegroundColor Green

    # Doi ten result
    $dest = "$EXAMPLE_DIR\result_$label.csv"
    if (Test-Path $dest) { Remove-Item $dest }
    if (Test-Path $RESULT_RAW) {
        Move-Item $RESULT_RAW $dest
        Write-Host "  [*] Result -> result_$label.csv" -ForegroundColor Green
    } else {
        Write-Warning "  [!] result CSV not found"
        Get-Content $simLog -Tail 8 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
    }

    # In WQB
    Select-String -Path $simLog -Pattern "\[WQB\]" | ForEach-Object {
        Write-Host "  $($_.Line)" -ForegroundColor Magenta
    }

    # Copy simulation_log.txt -> simulation_log_$label.txt NGAY sau khi chay
    # Phai copy truoc khi lan chay tiep theo ghi de
    $logDest = "$WORKSPACE\simulation_log_$label.txt"
    if (Test-Path $simLog) {
        Copy-Item $simLog $logDest -Force
        Write-Host "  [*] Log  -> simulation_log_$label.txt" -ForegroundColor Green
    }
}

# =========================================================
# MAIN
# =========================================================

Write-Host "======================================" -ForegroundColor White
Write-Host " MSH-OR Experiment Runner" -ForegroundColor White
Write-Host "======================================" -ForegroundColor White

Set-Location $WORKSPACE

if (-not (Test-Path $CP_FILE)) {
    mvn "dependency:build-classpath" "-Dmdep.outputFile=cp.txt" -q
}

foreach ($label in @("mshor", "random", "firstfit")) {
    $nosClass = $NOS[$label]
    Write-Host "`n======================================" -ForegroundColor White
    Write-Host " $nosClass ($label)" -ForegroundColor White
    Write-Host "======================================" -ForegroundColor White

    Switch-NOS $nosClass

    # Verify switch thanh cong truoc khi build
    $line = Select-String -Path $SIMPLE_EXAMPLE -Pattern "new [A-Za-z]+NOS\(\)" | Select-Object -First 1
    Write-Host "  [verify] SimpleExample.java: $($line.Line.Trim())" -ForegroundColor DarkCyan

    Build
    Run-Simulation $label
}

Write-Host "`n======================================" -ForegroundColor White
Write-Host " Parsing results..." -ForegroundColor White
Write-Host "======================================" -ForegroundColor White

python "$WORKSPACE\parse_results.py"

Write-Host "`n[DONE] Xong!" -ForegroundColor Green
Write-Host "`nWQB summary:" -ForegroundColor White
foreach ($label in @("mshor", "random", "firstfit")) {
    $log = "$WORKSPACE\simulation_log_$label.txt"
    if (Test-Path $log) {
        Select-String -Path $log -Pattern "\[WQB\]" | ForEach-Object {
            Write-Host "  $($_.Line)" -ForegroundColor Magenta
        }
    }
}