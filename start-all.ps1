# start-all.ps1 — Khởi động 5 service Haizz Exchange trong Windows Terminal (mỗi service 1 tab)
#
# Yêu cầu trước: hạ tầng Docker đã chạy (postgres/timescale/redis/kafka):
#     docker compose up -d
#
# Cách chạy (từ thư mục gốc repo):
#     pwsh -ExecutionPolicy Bypass -File .\start-all.ps1
# hoặc trong PowerShell:
#     .\start-all.ps1

$ErrorActionPreference = 'Stop'

$root     = $PSScriptRoot
$services = Join-Path $root 'services'
$env:SPRING_PROFILES_ACTIVE = 'dev'

# --- kiểm tra Windows Terminal ---
if (-not (Get-Command wt -ErrorAction SilentlyContinue)) {
    Write-Error "Không tìm thấy 'wt' (Windows Terminal). Cài từ Microsoft Store rồi chạy lại."
    exit 1
}

# --- cảnh báo nếu hạ tầng Docker chưa sẵn sàng ---
$infra = docker compose ps --services --filter status=running 2>$null
foreach ($svc in 'postgres','postgres-timescale','redis','kafka') {
    if ($infra -notcontains $svc) {
        Write-Warning "Hạ tầng '$svc' chưa chạy. Chạy 'docker compose up -d' trước khi start service."
    }
}

# --- giải phóng các port cũ (để start lại sạch) ---
foreach ($p in 8080, 8081, 8082, 8085, 3000) {
    $conns = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if ($conns) {
        $conns.OwningProcess | Select-Object -Unique | ForEach-Object {
            try { Stop-Process -Id $_ -Force -ErrorAction Stop; Write-Host "Đã giải phóng port $p (PID $_)" -ForegroundColor Yellow } catch {}
        }
    }
}

# --- mở 5 tab Windows Terminal ---
# Java service dùng env SPRING_PROFILES_ACTIVE=dev (kế thừa từ session này);
# KHÔNG dùng -Dspring-boot.run.profiles=dev vì mvn.cmd trên Windows tách chuỗi ở dấu '.'.
$run = 'mvn spring-boot:run'

$wtArgs = @(
    'new-tab', '--title', 'Auth',       '-d', (Join-Path $services 'auth'),       'pwsh', '-NoExit', '-Command', $run,
    ';', 'new-tab', '--title', 'Wallet',     '-d', (Join-Path $services 'wallet'),     'pwsh', '-NoExit', '-Command', $run,
    ';', 'new-tab', '--title', 'Gateway',    '-d', (Join-Path $services 'gateway'),    'pwsh', '-NoExit', '-Command', $run,
    ';', 'new-tab', '--title', 'MarketData', '-d', (Join-Path $services 'marketdata'), 'pwsh', '-NoExit', '-Command', $run,
    ';', 'new-tab', '--title', 'Frontend',   '-d', (Join-Path $services 'frontend'),   'pwsh', '-NoExit', '-Command', 'npm run dev'
)

Start-Process wt -ArgumentList $wtArgs

Write-Host ""
Write-Host "Đã mở 5 tab trong Windows Terminal (profile=dev):" -ForegroundColor Green
Write-Host "  Auth        -> http://localhost:8081"
Write-Host "  Wallet      -> http://localhost:8082"
Write-Host "  Gateway     -> http://localhost:8080"
Write-Host "  MarketData  -> http://localhost:8085  (chờ 1-5 phút backfill lần đầu)"
Write-Host "  Frontend    -> http://localhost:3000"
Write-Host ""
Write-Host "Lần đầu Java service sẽ compile, đợi ~20-40s mỗi cái. Tắt: đóng tab hoặc Ctrl+C trong tab."
