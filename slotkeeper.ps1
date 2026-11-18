#!/usr/bin/env pwsh
#requires -Version 7.4
<#
.SYNOPSIS
Builds, verifies and runs only this checkout's owned local Slotkeeper resources.
.DESCRIPTION
Credentials live in an ignored private runtime file. No global Docker cleanup, remote daemon,
public publication or volume deletion is performed. Failed verification is never converted to PASS.
#>
param(
    [Parameter(Position=0)]
    [ValidateSet('up','verify','rehearse','down','status')]
    [string]$Action = 'up'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$runtimeDirectory = Join-Path $projectRoot '.local/runtime'
$environmentFile = Join-Path $runtimeDirectory 'compose.env'
$utf8 = [Text.UTF8Encoding]::new($false)

function Invoke-Checked {
    param([string]$Command, [string[]]$CommandArguments)
    & $Command @CommandArguments
    if ($LASTEXITCODE -ne 0) { throw "Local command failed: $Command (exit $LASTEXITCODE)." }
}

function Assert-LocalDocker {
    Invoke-Checked 'docker' @('info','--format','{{.ServerVersion}}')
    $context = & docker context inspect --format '{{json .Endpoints.docker.Host}}'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the selected Docker context.' }
    $endpoint = $context | ConvertFrom-Json
    $candidates = @($endpoint)
    if ($env:DOCKER_HOST) { $candidates += $env:DOCKER_HOST }
    foreach ($candidate in $candidates) {
        $localEndpoint = $candidate -match '^unix:///' -or $candidate -match '^npipe:/{2,4}\./pipe/[a-zA-Z0-9._-]+$' -or
            $candidate -match '^tcp://(127\.0\.0\.1|localhost|\[::1\]):[0-9]+$'
        if (-not $localEndpoint) { throw 'Only a local Docker endpoint is accepted.' }
    }
}

function Assert-Toolchains {
    # Process-local selection; no NVM scripts or global machine configuration are changed.
    $javaCandidate = $env:JAVA_HOME
    if (-not $javaCandidate -or -not (Test-Path (Join-Path $javaCandidate 'bin/java.exe'))) {
        $javaCandidate = 'C:/Program Files/Java/jdk-21'
    }
    if (-not (Test-Path (Join-Path $javaCandidate 'bin/java.exe'))) { throw 'Set JAVA_HOME to a Java 21 JDK.' }
    $javaVersion = (& (Join-Path $javaCandidate 'bin/java.exe') -version 2>&1 | Out-String)
    if ($javaVersion -notmatch 'version "21[.\"]') {
        $javaCandidate = 'C:/Program Files/Java/jdk-21'
        if (-not (Test-Path (Join-Path $javaCandidate 'bin/java.exe'))) { throw 'Java 21 is required.' }
    }
    $env:JAVA_HOME = $javaCandidate
    $nodeVersion = & node --version
    if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v24\.') { throw 'Node 24 is required.' }
    $wrapperHash = (Get-FileHash (Join-Path $projectRoot 'gradle/wrapper/gradle-wrapper.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($wrapperHash -ne '423cb469ccc0ecc31f0e4e1c309976198ccb734cdcbb7029d4bda0f18f57e8d9') { throw 'Gradle wrapper integrity check failed.' }
}

function New-Secret {
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToHexString($bytes).ToLowerInvariant()
}

function Read-LocalConfiguration {
    if (-not (Test-Path -LiteralPath $environmentFile)) {
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $suffix = [Convert]::ToHexString($sha.ComputeHash($utf8.GetBytes($projectRoot.ToLowerInvariant()))).Substring(0,8).ToLowerInvariant() }
        finally { $sha.Dispose() }
        $configuration = [ordered]@{
            SLOTKEEPER_PROJECT_NAME = "slotkeeper-$suffix"
            SLOTKEEPER_OWNER_ID = [guid]::NewGuid().ToString()
            SLOTKEEPER_OWNER_PASSWORD = New-Secret
            SLOTKEEPER_MIGRATION_PASSWORD = New-Secret
            SLOTKEEPER_APP_PASSWORD = New-Secret
            SLOTKEEPER_APP_INSTANCE_ID = [guid]::NewGuid().ToString()
            SLOTKEEPER_APP2_INSTANCE_ID = [guid]::NewGuid().ToString()
            SLOTKEEPER_TRAINING_INSTANCE_ID = [guid]::NewGuid().ToString()
        }
        $lines = $configuration.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
        [IO.File]::WriteAllText($environmentFile, ($lines -join "`n") + "`n", $utf8)
    }
    $configuration = @{}
    foreach ($line in [IO.File]::ReadAllLines($environmentFile, $utf8)) {
        if ($line -match '^(SLOTKEEPER_[A-Z0-9_]+)=([a-zA-Z0-9-]+)$') { $configuration[$Matches[1]] = $Matches[2] }
        elseif ($line.Trim()) { throw 'Invalid private local configuration format.' }
    }
    foreach ($key in @('SLOTKEEPER_PROJECT_NAME','SLOTKEEPER_OWNER_ID','SLOTKEEPER_OWNER_PASSWORD','SLOTKEEPER_MIGRATION_PASSWORD','SLOTKEEPER_APP_PASSWORD','SLOTKEEPER_APP_INSTANCE_ID','SLOTKEEPER_APP2_INSTANCE_ID','SLOTKEEPER_TRAINING_INSTANCE_ID')) {
        if (-not $configuration.ContainsKey($key)) { throw "Missing local configuration key: $key." }
    }
    return $configuration
}

function Invoke-Compose {
    param([string[]]$ComposeArguments)
    # Compose shell values override --env-file. Supply only the already validated identities/secrets.
    $controlled = Read-LocalConfiguration
    $saved = @{}
    try {
        foreach ($key in $controlled.Keys) {
            $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $controlled[$key], 'Process')
        }
        Invoke-Checked 'docker' (@('compose','--project-name',$controlled.SLOTKEEPER_PROJECT_NAME,'--env-file',$environmentFile,'--project-directory',$projectRoot,'--file',(Join-Path $projectRoot 'compose.yaml')) + $ComposeArguments)
    } finally {
        foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
    }
}

function Assert-OwnedResources {
    param([hashtable]$Configuration)
    $projectName = $Configuration.SLOTKEEPER_PROJECT_NAME
    $ownerId = $Configuration.SLOTKEEPER_OWNER_ID
    $containers = @(& docker ps --all --quiet --filter "label=com.docker.compose.project=$projectName")
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect local container ownership.' }
    foreach ($container in $containers) {
        if (-not $container) { continue }
        $labels = (& docker inspect --format '{{json .Config.Labels}}' $container) | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0 -or $labels.'io.slotkeeper.owner' -ne $ownerId) { throw 'A matching project has a foreign ownership label; refusing to modify it.' }
    }
    $volumes = @(& docker volume ls --quiet --filter "label=com.docker.compose.project=$projectName")
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect local volume ownership.' }
    foreach ($volume in $volumes) {
        if (-not $volume) { continue }
        $labels = (& docker volume inspect --format '{{json .Labels}}' $volume) | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0 -or $labels.'io.slotkeeper.owner' -ne $ownerId) { throw 'A matching volume has a foreign ownership label; refusing to reuse it.' }
    }
    $networks = @(& docker network ls --quiet --filter "label=com.docker.compose.project=$projectName")
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect local network ownership.' }
    foreach ($network in $networks) {
        if (-not $network) { continue }
        $labels = (& docker network inspect --format '{{json .Labels}}' $network) | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0 -or $labels.'io.slotkeeper.owner' -ne $ownerId) { throw 'A matching network has a foreign ownership label; refusing reuse.' }
    }
    # Compose can reuse an unlabeled resource at an expected name. Check the exact names as well.
    $allVolumes = @(& docker volume ls --format '{{.Name}}')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot list local volume names.' }
    $volumeName = "${projectName}_database"
    if ($allVolumes -contains $volumeName) {
        $labels = (& docker volume inspect --format '{{json .Labels}}' $volumeName) | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0 -or -not $labels.PSObject.Properties['io.slotkeeper.owner'] -or $labels.'io.slotkeeper.owner' -ne $ownerId) { throw 'The expected database volume has no matching owner; refusing reuse.' }
    }
    $allNetworks = @(& docker network ls --format '{{.Name}}')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot list local network names.' }
    foreach ($networkName in @("${projectName}_private", "${projectName}_teaching")) {
        if ($allNetworks -contains $networkName) {
            $labels = (& docker network inspect --format '{{json .Labels}}' $networkName) | ConvertFrom-Json
            if ($LASTEXITCODE -ne 0 -or -not $labels.PSObject.Properties['io.slotkeeper.owner'] -or $labels.'io.slotkeeper.owner' -ne $ownerId) { throw 'An expected network has no matching owner; refusing reuse.' }
        }
    }
}

function Wait-OwnedInstance {
    param([int]$Port,[string]$ExpectedId,[string]$ExpectedProject='Slotkeeper')
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $marker = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/instance" -TimeoutSec 3
            if ($marker.instanceId -ne $ExpectedId -or $marker.project -ne $ExpectedProject) { throw 'Foreign instance marker.' }
            return
        } catch {
            if ($_.Exception.Message -eq 'Foreign instance marker.') { throw }
            Start-Sleep -Milliseconds 500
        }
    }
    throw "Owned instance on port $Port did not become ready. Inspect this project's local container logs."
}

function Build-Product {
    Invoke-Checked (Join-Path $projectRoot 'gradlew.bat') @('--console=plain','--no-daemon','--dependency-verification=strict',':application:bootJar',':verification:installDist',':training-target:installDist')
}

function Verify-Product {
    if ($Action -eq 'rehearse') {
        $changes = @(& git -C $projectRoot status --porcelain --untracked-files=normal)
        if ($LASTEXITCODE -ne 0 -or $changes.Count -ne 0) {
            throw 'Commit the intended source changes before a revision-matched rehearsal.'
        }
    }
    Invoke-Checked (Join-Path $PSHOME 'pwsh.exe') @('-NoProfile','-File',(Join-Path $projectRoot 'scripts/tests/Launcher.Tests.ps1'))
    $revision = (& git -C $projectRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'A local Git revision is required for traceable evidence.' }
    $env:SLOTKEEPER_REVISION = $revision
    $env:SLOTKEEPER_CONTENTION_OUTPUT = Join-Path $runtimeDirectory 'database-contention.json'
    Invoke-Checked (Join-Path $projectRoot 'gradlew.bat') @('--console=plain','--no-daemon','--dependency-verification=strict','clean','verify',':application:bootJar',':verification:installDist',':training-target:installDist')
    if (-not (Test-Path -LiteralPath $env:SLOTKEEPER_CONTENTION_OUTPUT)) { throw 'Verification produced no independent-process contention evidence.' }
    Push-Location (Join-Path $projectRoot 'web')
    try { Invoke-Checked 'npm.cmd' @('run','format:check') } finally { Pop-Location }
    if ($Action -eq 'rehearse') {
        $changes = @(& git -C $projectRoot status --porcelain --untracked-files=normal)
        if ($LASTEXITCODE -ne 0 -or $changes.Count -ne 0) {
            throw 'Verification changed the source tree; commit the reviewed changes and rerun.'
        }
    }
}

[IO.Directory]::CreateDirectory($runtimeDirectory) | Out-Null
$launcherLock = $null
$previousJava = $env:JAVA_HOME
$previousRevision = $env:SLOTKEEPER_REVISION
$previousEvidence = $env:SLOTKEEPER_CONTENTION_OUTPUT
$previousProbePassword = $env:SLOTKEEPER_PROBE_DB_PASSWORD
Push-Location $projectRoot
try {
    $launcherLock = [IO.File]::Open((Join-Path $runtimeDirectory 'launcher.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    Assert-LocalDocker
    if (($Action -eq 'status' -or $Action -eq 'down') -and -not (Test-Path -LiteralPath $environmentFile)) {
        Write-Host 'This checkout has no private local runtime configuration. Existing resources are preserved.'
        return
    }
    if ($Action -eq 'verify') { Assert-Toolchains; Verify-Product; Write-Host 'Locked local verification passed.'; return }
    $configuration = Read-LocalConfiguration
    Assert-OwnedResources $configuration
    Invoke-Compose @('config','--quiet')
    switch ($Action) {
        'up' {
            Assert-Toolchains; Build-Product
            Invoke-Compose @('up','--detach','--build','--wait','--wait-timeout','150','app')
            Wait-OwnedInstance 4190 $configuration.SLOTKEEPER_APP_INSTANCE_ID
            Write-Host 'Slotkeeper is ready: http://127.0.0.1:4190'
        }
        'rehearse' {
            Assert-Toolchains; Verify-Product
            $primaryFailure = $null
            try {
                Invoke-Compose @('--profile','rehearsal','up','--detach','--build','--wait','--wait-timeout','150','app','app2','training')
                Wait-OwnedInstance 4190 $configuration.SLOTKEEPER_APP_INSTANCE_ID
                Wait-OwnedInstance 4192 $configuration.SLOTKEEPER_APP2_INSTANCE_ID
                Wait-OwnedInstance 4193 $configuration.SLOTKEEPER_TRAINING_INSTANCE_ID 'Slotkeeper Teaching Target'
                $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
                $output = Join-Path $projectRoot "artifacts/rehearsal/$stamp"
                $runner = Join-Path $projectRoot 'verification/build/install/verification/bin/verification.bat'
                $env:SLOTKEEPER_PROBE_DB_PASSWORD = $configuration.SLOTKEEPER_APP_PASSWORD
                Invoke-Checked $runner @('--app','http://127.0.0.1:4190','--app-instance',$configuration.SLOTKEEPER_APP_INSTANCE_ID,'--app2','http://127.0.0.1:4192','--app2-instance',$configuration.SLOTKEEPER_APP2_INSTANCE_ID,'--training','http://127.0.0.1:4193','--training-instance',$configuration.SLOTKEEPER_TRAINING_INSTANCE_ID,'--output',$output,'--revision',$env:SLOTKEEPER_REVISION,'--contention-evidence',$env:SLOTKEEPER_CONTENTION_OUTPUT,'--database-url','jdbc:postgresql://127.0.0.1:55419/slotkeeper?currentSchema=slotkeeper')
            } catch { $primaryFailure = $_ }
            finally {
                try {
                    Assert-OwnedResources $configuration
                    Invoke-Compose @('--profile','rehearsal','stop','app2','training')
                } catch {
                    if ($null -eq $primaryFailure) { $primaryFailure = $_ }
                    else { Write-Warning 'Secondary cleanup also failed. Inspect owned app2/training resources before another run.' }
                }
            }
            if ($null -ne $primaryFailure) { throw $primaryFailure }
            Write-Host "Observed rehearsal evidence: $output"
        }
        'down' { Invoke-Compose @('--profile','rehearsal','down'); Write-Host 'Owned Slotkeeper containers stopped; the local database volume is retained.' }
        'status' { Invoke-Compose @('--profile','rehearsal','ps','--all') }
    }
} finally {
    $env:JAVA_HOME = $previousJava
    $env:SLOTKEEPER_REVISION = $previousRevision
    $env:SLOTKEEPER_CONTENTION_OUTPUT = $previousEvidence
    $env:SLOTKEEPER_PROBE_DB_PASSWORD = $previousProbePassword
    if ($launcherLock) { $launcherLock.Dispose() }
    Pop-Location
}
