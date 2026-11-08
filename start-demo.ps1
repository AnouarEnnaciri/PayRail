$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Set-Location $root

if (-not (Test-Path (Join-Path $root ".env"))) {
    throw "Fichier PayRail/.env manquant"
}

# Docker Compose automatically reads PayRail/.env for variable substitution.
# Do not print or commit credentials.
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build
if ($LASTEXITCODE -ne 0) {
    throw "Impossible de demarrer le profil demo Docker"
}

Write-Host "Compose lance. Controle: docker compose -f docker-compose.yml -f docker-compose.demo.yml ps"
Write-Host "API locale: http://localhost:8080/actuator/health"
Write-Host "Swagger local: http://localhost:8080/swagger-ui.html"
