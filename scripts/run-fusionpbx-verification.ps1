param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$DatabaseUrl = 'jdbc:postgresql://localhost:25432/voip_fusion_sim'
)

$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$javaExecutable = Join-Path $JavaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable)) { throw 'Set JAVA_HOME to a JDK 25 installation.' }
$postmanExecutable = (Get-Command postman -ErrorAction Stop).Source
$originalLocation = Get-Location
$originalEnvironment = @{}
$verificationEnvironment = @{
    FUSIONPBX_API_URL = 'http://127.0.0.1:14010/fusionpbx/app/rest_api/rest.php'
    FUSIONPBX_API_TOKEN = '22222222-2222-2222-2222-222222222222:simulation-only-secret'
    FUSIONPBX_CREDENTIALS_KEY = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
    FUSIONPBX_REQUEST_TIMEOUT = '400ms'
    FUSIONPBX_DOMAIN_NAME = '10.155.199.136'
    DB_URL = $DatabaseUrl
}
$simulationProcess = $null
$backendProcess = $null

function Wait-VerificationEndpoint([string]$Uri, [System.Diagnostics.Process]$Process) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($Process.HasExited) { throw "Verification process exited. See target/fusion-simulation*.log or target/fusion-backend*.log." }
        try {
            Invoke-RestMethod $Uri -TimeoutSec 1 | Out-Null
            return
        } catch {}
        Start-Sleep -Milliseconds 250
    }
    throw "Verification endpoint did not become ready: $Uri"
}

try {
    Set-Location -LiteralPath $backendRoot
    foreach ($key in $verificationEnvironment.Keys) {
        $originalEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $verificationEnvironment[$key], 'Process')
    }
    $simulationProcess = Start-Process -FilePath $postmanExecutable -WindowStyle Hidden -PassThru `
        -ArgumentList @('simulation', 'run', 'postman/simulations/fusionpbx-dev.sim.yaml', '--port', '14010', '--output', 'ndjson', '--no-history') `
        -RedirectStandardOutput 'target/fusion-simulation.log' -RedirectStandardError 'target/fusion-simulation.err.log'
    Wait-VerificationEndpoint 'http://127.0.0.1:14010/fusionpbx/__control' $simulationProcess
    $backendProcess = Start-Process -FilePath $javaExecutable -WindowStyle Hidden -PassThru `
        -ArgumentList @('-jar', 'target/backend-voip-0.0.1-SNAPSHOT.jar', '--server.port=18082', '--server.address=127.0.0.1') `
        -RedirectStandardOutput 'target/fusion-backend.log' -RedirectStandardError 'target/fusion-backend.err.log'
    Wait-VerificationEndpoint 'http://127.0.0.1:18082/actuator/health' $backendProcess
    node scripts/verify-fusionpbx.mjs
    if ($LASTEXITCODE -ne 0) { throw "Backend simulation assertions failed with exit code $LASTEXITCODE." }
} finally {
    foreach ($process in @($backendProcess, $simulationProcess)) {
        if ($null -ne $process -and -not $process.HasExited) {
            $process.Kill($true)
            $null = $process.WaitForExit(5000)
        }
    }
    foreach ($key in $originalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($key, $originalEnvironment[$key], 'Process')
    }
    Set-Location -LiteralPath $originalLocation
}
