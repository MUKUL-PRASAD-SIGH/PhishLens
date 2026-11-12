# Manual scaling demo for the scan-api Deployment (PowerShell version of demo-scale.sh).
#
# Usage:
#   .\scripts\demo-scale.ps1                          # default sequence 3 -> 4 -> 3 (the sequence demonstrated)
#   .\scripts\demo-scale.ps1 -Sequence 3,5,2          # the TEAM_PLAN sequence, if the cluster has room for 5 pods
#   .\scripts\demo-scale.ps1 -SuspendHpa              # delete the HPA for the demo, re-apply k8s/hpa.yaml afterwards
#
# The default is 3 -> 4 -> 3 because the Minikube container on the demo laptop is capped at
# 2 CPUs / 2 GiB by Docker; a 5th scan-api pod does not fit. The HPA (minReplicas 3) overrides
# kubectl scale, so the script refuses to run while it exists unless -SuspendHpa is given.
param(
    [int[]]$Sequence = @(3, 4, 3),
    [switch]$SuspendHpa,
    [string]$Namespace = "phishlens",
    [string]$RolloutTimeout = "600s"
)

# Native kubectl failures are checked through $LASTEXITCODE. "Stop" would turn redirected
# stderr into terminating errors in Windows PowerShell 5.1.
$ErrorActionPreference = "Continue"
$Deployment = "scan-api"
$HpaName = "scan-api-hpa"
$HpaManifest = Join-Path $PSScriptRoot "..\k8s\hpa.yaml"

kubectl get namespace $Namespace 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Namespace '$Namespace' not found in the current kubectl context." }
kubectl -n $Namespace get deployment $Deployment 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Deployment '$Deployment' not found." }

function Show-State {
    $desired = kubectl -n $Namespace get deployment $Deployment -o jsonpath='{.spec.replicas}'
    $ready = kubectl -n $Namespace get deployment $Deployment -o jsonpath='{.status.readyReplicas}'
    if (-not $ready) { $ready = 0 }
    Write-Host "scan-api ready replicas: $ready/$desired"
    kubectl -n $Namespace get pods -l app=scan-api -o wide
}

function Set-ReplicaCount {
    param([int]$Count)

    Write-Host "`n=== $(Get-Date -Format HH:mm:ss) Scaling scan-api to $Count replicas" -ForegroundColor Cyan
    kubectl -n $Namespace scale deployment $Deployment "--replicas=$Count"
    if ($LASTEXITCODE -ne 0) { throw "Scaling command failed." }

    kubectl -n $Namespace rollout status "deployment/$Deployment" "--timeout=$RolloutTimeout"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Pods that are not Running:" -ForegroundColor Yellow
        kubectl -n $Namespace get pods -l app=scan-api --field-selector=status.phase!=Running
        Write-Host "Recent FailedScheduling events:" -ForegroundColor Yellow
        kubectl -n $Namespace get events --field-selector=reason=FailedScheduling --sort-by=.lastTimestamp
        throw "Scaling to $Count did not complete within $RolloutTimeout. Scale back with: kubectl -n $Namespace scale deployment $Deployment --replicas=3"
    }
    Show-State
}

$hpaRemoved = $false
try {
    kubectl -n $Namespace get hpa $HpaName 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) {
        if (-not $SuspendHpa) {
            throw "HPA '$HpaName' exists and would override manual scaling (minReplicas 3). Re-run with -SuspendHpa, or delete it first: kubectl -n $Namespace delete hpa $HpaName"
        }
        if (-not (Test-Path $HpaManifest)) { throw "Cannot suspend the HPA: $HpaManifest not found, so it could not be restored." }
        Write-Host "Suspending HPA '$HpaName' for the manual scaling demo (it is re-applied at the end)."
        kubectl -n $Namespace delete hpa $HpaName
        if ($LASTEXITCODE -ne 0) { throw "Could not delete the HPA." }
        $hpaRemoved = $true
    }

    Write-Host "PhishLens manual scaling demo: $($Sequence -join ' -> ') (namespace $Namespace)"
    Write-Host "`n=== Starting state"
    Show-State

    foreach ($count in $Sequence) { Set-ReplicaCount $count }

    Write-Host "`nScaling demo completed: $($Sequence -join ' -> ')." -ForegroundColor Green
}
finally {
    if ($hpaRemoved) {
        Write-Host "`nRe-applying the HPA from $HpaManifest ..."
        kubectl apply -f $HpaManifest
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not re-apply the HPA; run: kubectl apply -f k8s/hpa.yaml" }
    }
}
