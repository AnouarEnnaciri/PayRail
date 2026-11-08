$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Test-Path ".env")) { throw "Fichier PayRail/.env manquant" }
docker compose -f docker-compose.yml -f docker-compose.demo.yml -f docker-compose.simulator.yml up -d --build
if ($LASTEXITCODE -ne 0) { throw "Echec du lancement M6" }
Write-Host "Backend: http://localhost:8080/actuator/health"
Write-Host "Simulator: http://localhost:8081/settlements/<request-id>"
