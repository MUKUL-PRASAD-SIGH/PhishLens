$ErrorActionPreference = "Stop"

$Namespace = "phishlens"
$Deployment = "scan-api"

function Set-ReplicaCount {
    param([int]$Count)

    Write-Host "`nScaling scan-api to $Count replicas..." -ForegroundColor Cyan

    kubectl -n $Namespace scale deployment $Deployment "--replicas=$Count"
    if ($LASTEXITCODE -ne 0) { throw "Scaling command failed." }

    kubectl -n $Namespace rollout status "deployment/$Deployment" --timeout=180s
    if ($LASTEXITCODE -ne 0) { throw "Rollout failed or timed out." }

    kubectl -n $Namespace get pods -l app=scan-api
    if ($LASTEXITCODE -ne 0) { throw "Could not list scan-api pods." }
}

Write-Host "Starting PhishLens scaling demo"

Set-ReplicaCount 3
Set-ReplicaCount 5
Set-ReplicaCount 2

Write-Host "`nScaling demo completed successfully." -ForegroundColor Green
