#!/usr/bin/env pwsh
#requires -Version 7.4
<#
.SYNOPSIS
Runs non-destructive launcher regressions without Pester or a real Docker command.
.DESCRIPTION
Each case starts an isolated PowerShell process and executes a copied launcher in an ignored
fixture checkout. A Docker function records the launcher's real command boundary; PATH is emptied
to prevent falling through to an installed Docker CLI. Rehearsal cases replace only toolchain,
build/verification work and readiness waits in the private copy. Fixtures and sanitized results
remain under .local/launcher-tests. No real containers, networks, volumes or application servers run.
#>
param(
    [string]$LauncherPath = (Join-Path $PSScriptRoot '../../slotkeeper.ps1'),
    [string]$OutputDirectory,
    [string]$ProbeCase,
    [string]$FixtureRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-FixtureConfiguration {
    return [ordered]@{
        SLOTKEEPER_PROJECT_NAME = 'slotkeeper-launcher-fixture'
        SLOTKEEPER_OWNER_ID = '11111111-1111-4111-8111-111111111111'
        SLOTKEEPER_OWNER_PASSWORD = 'synthetic-owner-fixture'
        SLOTKEEPER_MIGRATION_PASSWORD = 'synthetic-migration-fixture'
        SLOTKEEPER_APP_PASSWORD = 'synthetic-app-fixture'
        SLOTKEEPER_APP_INSTANCE_ID = '22222222-2222-4222-8222-222222222222'
        SLOTKEEPER_APP2_INSTANCE_ID = '33333333-3333-4333-8333-333333333333'
        SLOTKEEPER_TRAINING_INSTANCE_ID = '44444444-4444-4444-8444-444444444444'
    }
}

function Assert-Test {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function New-FixtureLauncher {
    param([string]$Source, [string]$Destination, [bool]$Rehearsal)
    $text = [IO.File]::ReadAllText($Source)
    if ($Rehearsal) {
        $tokens = $null
        $parseErrors = $null
        $tree = [Management.Automation.Language.Parser]::ParseInput($text, [ref]$tokens, [ref]$parseErrors)
        if ($parseErrors.Count -ne 0) { throw 'The launcher snapshot does not parse.' }
        $replacements = @{
            'Assert-Toolchains' = 'function Assert-Toolchains {}'
            'Build-Product' = 'function Build-Product {}'
            'Verify-Product' = @'
function Verify-Product {
    $env:SLOTKEEPER_REVISION = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
    $env:SLOTKEEPER_CONTENTION_OUTPUT = Join-Path $runtimeDirectory 'database-contention.json'
}
'@
            'Wait-OwnedInstance' = @'
function Wait-OwnedInstance {
    param([int]$Port, [string]$ExpectedId, [string]$ExpectedProject = 'Slotkeeper')
    if ($Port -eq 4192 -and $global:LauncherProbeCase -like '*readiness*') {
        throw 'Synthetic readiness failure at port 4192.'
    }
}
'@
        }
        $functions = @($tree.FindAll({ param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst]
        }, $false) | Where-Object { $replacements.ContainsKey($_.Name) })
        if ($functions.Count -ne $replacements.Count) { throw 'Required external-work seams were not found.' }
        foreach ($function in ($functions | Sort-Object { $_.Extent.StartOffset } -Descending)) {
            $extent = $function.Extent
            $text = $text.Remove($extent.StartOffset, $extent.EndOffset - $extent.StartOffset).
                Insert($extent.StartOffset, $replacements[$function.Name])
        }
    }
    [IO.File]::WriteAllText($Destination, $text, [Text.UTF8Encoding]::new($false))
}

function Invoke-FixtureProbe {
    param([string]$Case, [string]$Root)
    $configuration = Get-FixtureConfiguration
    $global:LauncherProbeConfiguration = $configuration
    $global:LauncherProbeCalls = [Collections.Generic.List[object]]::new()
    $global:LauncherUnexpectedDockerCalls = [Collections.Generic.List[string]]::new()
    $global:LauncherProbeCase = $Case
    $global:LASTEXITCODE = 0

    # No installed executable can be found if the command mock stops resolving.
    $emptyPath = Join-Path $Root 'empty-path'
    [IO.Directory]::CreateDirectory($emptyPath) | Out-Null
    $env:PATH = $emptyPath
    $env:DOCKER_HOST = if ($Case -eq 'remote-host') { 'ssh://synthetic.invalid' } else { $null }

    $before = @{}
    $env:COMPOSE_PROJECT_NAME = 'foreign-inherited-project'
    $env:SLOTKEEPER_PROBE_DB_PASSWORD = 'foreign-inherited-value'
    foreach ($key in $configuration.Keys) {
        [Environment]::SetEnvironmentVariable($key, 'foreign-inherited-value', 'Process')
    }
    foreach ($key in (@('COMPOSE_PROJECT_NAME','SLOTKEEPER_PROBE_DB_PASSWORD') + @($configuration.Keys))) {
        $before[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    }

    function global:docker {
        $arguments = @($args | ForEach-Object { [string]$_ })
        $global:LASTEXITCODE = 0
        if ($arguments.Count -eq 0) { throw 'Empty mocked Docker command.' }

        switch ($arguments[0]) {
            'info' { return 'synthetic-local-daemon' }
            'context' {
                $endpoint = switch ($global:LauncherProbeCase) {
                    'remote-context' { 'ssh://synthetic.invalid' }
                    'remote-npipe-unc' { 'npipe:////synthetic-host/pipe/docker_engine' }
                    'remote-tcp-userinfo' { 'tcp://synthetic-user@127.0.0.1:2375' }
                    default { 'npipe:////./pipe/synthetic-local-docker' }
                }
                return ConvertTo-Json -InputObject $endpoint -Compress
            }
            'ps' {
                if ($global:LauncherProbeCase -eq 'foreign-container') { return 'synthetic-container' }
                return
            }
            'inspect' {
                $labels = @{
                    'com.docker.compose.project' = 'slotkeeper-launcher-fixture'
                    'io.slotkeeper.owner' = '99999999-9999-4999-8999-999999999999'
                }
                return ConvertTo-Json -InputObject $labels -Compress
            }
            { $_ -in @('volume', 'network') } {
                if ($arguments.Count -lt 2) { throw 'Incomplete mocked Docker resource command.' }
                if ($arguments[1] -eq 'ls') {
                    # Unlabeled collisions are absent from label-filtered inventories but still
                    # present in an unfiltered name inventory, as on a real Docker daemon.
                    if (@($arguments | Where-Object { $_ -like '--filter*' }).Count -gt 0) { return }
                    if ($arguments[0] -eq 'volume' -and $global:LauncherProbeCase -eq 'unlabeled-volume') {
                        return 'slotkeeper-launcher-fixture_database'
                    }
                    if ($arguments[0] -eq 'network') {
                        if ($global:LauncherProbeCase -eq 'unlabeled-private-network') { return 'slotkeeper-launcher-fixture_private' }
                        if ($global:LauncherProbeCase -eq 'unlabeled-teaching-network') { return 'slotkeeper-launcher-fixture_teaching' }
                    }
                    return
                }
                if ($arguments[1] -eq 'inspect') {
                    $name = @($arguments | Where-Object { $_ -like 'slotkeeper-launcher-fixture_*' })
                    $collision = switch ($global:LauncherProbeCase) {
                        'unlabeled-volume' { 'slotkeeper-launcher-fixture_database' }
                        'unlabeled-private-network' { 'slotkeeper-launcher-fixture_private' }
                        'unlabeled-teaching-network' { 'slotkeeper-launcher-fixture_teaching' }
                        default { '' }
                    }
                    if ($name.Count -ne 1 -or $name[0] -ne $collision) {
                        $global:LASTEXITCODE = 1
                        return
                    }
                    $global:LauncherProbeCalls.Add([pscustomobject]@{ Kind = 'collision-inspected'; Operation = 'inspect' })
                    if ($arguments -contains '--format') { return '{}' }
                    return ConvertTo-Json -InputObject @(@{ Name = $collision; Labels = @{} }) -Compress
                }
            }
            'compose' {
                $operation = @($arguments | Where-Object {
                    $_ -in @('config', 'ps', 'up', 'down', 'stop', 'rm', 'create', 'start', 'kill', 'restart', 'run', 'build')
                }) | Select-Object -First 1
                $projectIndex = [Array]::IndexOf($arguments, '--project-name')
                if ($projectIndex -lt 0) { $projectIndex = [Array]::IndexOf($arguments, '-p') }
                $effectiveProject = if ($projectIndex -ge 0) { $arguments[$projectIndex + 1] }
                    elseif ($env:COMPOSE_PROJECT_NAME) { $env:COMPOSE_PROJECT_NAME }
                    else { $env:SLOTKEEPER_PROJECT_NAME }
                $matchesConfiguration = $effectiveProject -eq 'slotkeeper-launcher-fixture'
                foreach ($key in $global:LauncherProbeConfiguration.Keys) {
                    $matchesConfiguration = $matchesConfiguration -and
                        ([Environment]::GetEnvironmentVariable($key, 'Process') -ceq $global:LauncherProbeConfiguration[$key])
                }
                $stopExtras = $operation -eq 'stop' -and
                    ($arguments -contains 'app2') -and ($arguments -contains 'training') -and
                    -not ($arguments -contains 'app')
                $global:LauncherProbeCalls.Add([pscustomobject]@{
                    Kind = 'compose'
                    Operation = $operation
                    ConfigurationMatches = $matchesConfiguration
                    StopsOnlyExtras = $stopExtras
                })
                if ($operation -eq 'up' -and $global:LauncherProbeCase -like '*partial-up*') {
                    $global:LASTEXITCODE = 73
                }
                if ($operation -eq 'stop' -and $global:LauncherProbeCase -like '*cleanup-fails') {
                    $global:LASTEXITCODE = 88
                }
                return
            }
        }
        $global:LauncherUnexpectedDockerCalls.Add($arguments[0])
        throw 'Unexpected mocked Docker command; no real command was invoked.'
    }

    $action = if ($Case -like '*partial-up*' -or $Case -like '*readiness*') { 'rehearse' }
        elseif ($Case -eq 'inherited-status') { 'status' } else { 'down' }
    $threw = $false
    $failureMessage = ''
    try { & (Join-Path $Root 'slotkeeper.ps1') $action *> $null }
    catch { $threw = $true; $failureMessage = $_.Exception.Message }
    # Sanitized diagnostics never retain even these public synthetic secret values.
    foreach ($value in $configuration.Values) { $failureMessage = $failureMessage.Replace($value, '[fixture]') }
    $failureMessage = $failureMessage.Replace('foreign-inherited-value', '[inherited]')
    $restored = $true
    foreach ($key in $before.Keys) {
        $restored = $restored -and ([Environment]::GetEnvironmentVariable($key, 'Process') -ceq $before[$key])
    }
    $composeCalls = @($global:LauncherProbeCalls | Where-Object { $_.Kind -eq 'compose' })
    $mutationCalls = @($composeCalls | Where-Object { $_.Operation -notin @('config', 'ps') })
    $result = [ordered]@{
        Case = $Case
        Threw = $threw
        FailureMessage = $failureMessage
        EnvironmentRestored = $restored
        ComposeCount = $composeCalls.Count
        MutationCount = $mutationCalls.Count
        ConfigurationMatches = @($composeCalls | Where-Object { -not $_.ConfigurationMatches }).Count -eq 0
        StopExtrasRequested = @($composeCalls | Where-Object { $_.StopsOnlyExtras }).Count -gt 0
        UpAttempted = @($composeCalls | Where-Object { $_.Operation -eq 'up' }).Count -gt 0
        CollisionInspected = @($global:LauncherProbeCalls | Where-Object { $_.Kind -eq 'collision-inspected' }).Count -gt 0
        UnexpectedDockerCalls = @($global:LauncherUnexpectedDockerCalls)
    }
    [IO.File]::WriteAllText((Join-Path $Root 'result.json'), ($result | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
}

if ($ProbeCase) {
    if (-not $FixtureRoot) { throw 'A private fixture root is required for a probe.' }
    Invoke-FixtureProbe $ProbeCase ([IO.Path]::GetFullPath($FixtureRoot))
    exit 0
}

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$sourcePath = [IO.Path]::GetFullPath($LauncherPath)
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repositoryRoot ('.local/launcher-tests/' + [guid]::NewGuid().ToString('N'))
}
$runRoot = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($runRoot) | Out-Null
$snapshot = Join-Path $runRoot 'launcher.snapshot.ps1'
[IO.File]::Copy($sourcePath, $snapshot, $false)
$snapshotHash = (Get-FileHash -LiteralPath $snapshot -Algorithm SHA256).Hash.ToLowerInvariant()
$pwsh = [IO.Path]::Combine($PSHOME, $(if ($IsWindows) { 'pwsh.exe' } else { 'pwsh' }))
$configuration = Get-FixtureConfiguration
$cases = @(
    'inherited-status', 'inherited-down',
    'unlabeled-volume', 'unlabeled-private-network', 'unlabeled-teaching-network',
    'foreign-container', 'remote-context', 'remote-host', 'remote-npipe-unc', 'remote-tcp-userinfo',
    'partial-up', 'readiness', 'partial-up-cleanup-fails', 'readiness-cleanup-fails'
)
$results = [Collections.Generic.List[object]]::new()
foreach ($case in $cases) {
    $fixture = Join-Path $runRoot $case
    $fixtureRuntime = Join-Path $fixture '.local/runtime'
    [IO.Directory]::CreateDirectory($fixtureRuntime) | Out-Null
    $rehearsal = $case -like '*partial-up*' -or $case -like '*readiness*'
    New-FixtureLauncher $snapshot (Join-Path $fixture 'slotkeeper.ps1') $rehearsal
    $lines = $configuration.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
    [IO.File]::WriteAllText((Join-Path $fixtureRuntime 'compose.env'), ($lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $fixture 'compose.yaml'), @'
name: ${SLOTKEEPER_PROJECT_NAME}
services: {}
volumes:
  database: {}
networks:
  private: {}
  teaching: {}
'@, [Text.UTF8Encoding]::new($false))
    $probeOutput = & $pwsh -NoLogo -NoProfile -NonInteractive -File $PSCommandPath -ProbeCase $case -FixtureRoot $fixture 2>&1
    $probeExit = $LASTEXITCODE
    $outcome = 'PASS'
    $message = ''
    try {
        Assert-Test ($probeExit -eq 0) 'The isolated probe failed before it could observe the launcher.'
        $observed = Get-Content -LiteralPath (Join-Path $fixture 'result.json') -Raw | ConvertFrom-Json
        Assert-Test ($observed.UnexpectedDockerCalls.Count -eq 0) 'An unmodeled Docker operation occurred; the probe refused it.'
        Assert-Test $observed.EnvironmentRestored 'The launcher did not restore inherited Compose/Slotkeeper environment values.'
        if ($case -like 'inherited-*') {
            Assert-Test (-not $observed.Threw) 'The valid owned fixture was unexpectedly refused.'
            Assert-Test ($observed.ComposeCount -ge 2) 'The real launcher did not reach Compose validation and the requested action.'
            Assert-Test $observed.ConfigurationMatches 'Inherited values redirected the effective Compose project or interpolation.'
            if ($case -eq 'inherited-down') { Assert-Test ($observed.MutationCount -eq 1) 'Owned shutdown was not requested exactly once.' }
        } elseif ($case -like 'unlabeled-*') {
            Assert-Test ($observed.MutationCount -eq 0) 'The launcher requested a Compose mutation despite an unlabeled exact-name resource collision.'
            Assert-Test $observed.Threw 'The exact-name unlabeled collision was not refused.'
            Assert-Test $observed.CollisionInspected 'The launcher never inspected the colliding exact-name resource.'
        } elseif ($case -eq 'foreign-container' -or $case -like 'remote-*') {
            Assert-Test $observed.Threw 'The foreign owner or remote endpoint was not refused.'
            Assert-Test ($observed.MutationCount -eq 0) 'A refused target still received a Compose mutation.'
            if ($case -eq 'foreign-container') {
                Assert-Test ($observed.FailureMessage -match 'foreign|ownership') 'The foreign container was not refused for an ownership reason.'
            } else {
                Assert-Test ($observed.FailureMessage -match 'local Docker endpoint') 'The remote target was not refused at the endpoint boundary.'
                Assert-Test ($observed.ComposeCount -eq 0) 'A remote target reached Compose before rejection.'
            }
        } else {
            Assert-Test $observed.UpAttempted 'The rehearsal never reached the simulated startup failure boundary.'
            Assert-Test $observed.StopExtrasRequested 'Rehearsal startup/readiness failure did not stop app2 and training.'
            Assert-Test $observed.Threw 'A failed rehearsal unexpectedly returned success.'
            $expectedFailure = if ($case -like '*partial-up*') { '*exit 73*' } else { '*Synthetic readiness failure at port 4192*' }
            Assert-Test ($observed.FailureMessage -like $expectedFailure) 'Cleanup masked the original rehearsal failure.'
        }
    } catch { $outcome = 'FAIL'; $message = $_.Exception.Message }
    $results.Add([pscustomobject]@{ Case = $case; Outcome = $outcome; Detail = $message })
    Write-Host ("{0} {1}{2}" -f $outcome, $case, $(if ($message) { ': ' + $message } else { '' }))
    if ($probeExit -ne 0) {
        $safeProbeError = $probeOutput | Out-String
        foreach ($value in $configuration.Values) { $safeProbeError = $safeProbeError.Replace($value, '[fixture]') }
        $safeProbeError = $safeProbeError.Replace('foreign-inherited-value', '[inherited]')
        [IO.File]::WriteAllText((Join-Path $fixture 'probe-error.txt'), $safeProbeError, [Text.UTF8Encoding]::new($false))
    }
}
$failed = @($results | Where-Object { $_.Outcome -eq 'FAIL' }).Count
$summary = [ordered]@{
    LauncherSha256 = $snapshotHash
    Total = $results.Count
    Passed = $results.Count - $failed
    Failed = $failed
    RealDockerInvoked = $false
    Results = @($results)
}
[IO.File]::WriteAllText((Join-Path $runRoot 'summary.json'), ($summary | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
Write-Host ("Launcher regressions: {0} passed, {1} failed. All Docker operations were mocked." -f ($results.Count - $failed), $failed)
Write-Host ("Sanitized fixture results: {0}" -f $runRoot)
if ($failed -gt 0) { exit 1 }
