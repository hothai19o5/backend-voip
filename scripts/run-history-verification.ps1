param(
  [string]$JavaHome = 'C:/Program Files/Java/jdk-25',
  [string]$DatabaseUrl = 'jdbc:postgresql://localhost:25432/voip_history_test',
  [string]$DatabaseUsername = 'root',
  [string]$DatabasePassword = 'voip@123'
)
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path $PSScriptRoot -Parent
$previousLocation = Get-Location
$savedEnvironment = @{}
$processes = @()
$settings = @{
  PORT = '14011'; DB_URL = $DatabaseUrl; DB_USERNAME = $DatabaseUsername; DB_PASSWORD = $DatabasePassword
  JWT_SECRET = 'history-http-verification-signing-key-at-least-32-bytes'
  FUSIONPBX_API_URL = 'http://127.0.0.1:14011/app/rest_api/rest.php'
  FUSIONPBX_API_TOKEN = '22222222-2222-2222-2222-222222222222:simulation-only-secret'
  FUSIONPBX_CREDENTIALS_KEY = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
  FUSIONPBX_DOMAIN_NAME = '10.155.199.136'; FUSIONPBX_SERVER_IP = '10.155.199.136'
}
function Wait-Ready([string]$url, $process) {
  for ($attempt = 0; $attempt -lt 80; $attempt++) {
    if ($process.HasExited) { throw 'Verification service exited; see target/history-http*.log.' }
    try { $null = Invoke-RestMethod -Uri $url -TimeoutSec 1; return } catch { Start-Sleep -Milliseconds 250 }
  }
  throw "Verification service did not start: $url"
}
function Start-Backend([string]$suffix) {
  $process = Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -WindowStyle Hidden -PassThru `
    -ArgumentList @('-jar', 'target/backend-voip-0.0.1-SNAPSHOT.jar', '--server.port=18083', '--server.address=127.0.0.1') `
    -RedirectStandardOutput "target/history-http-$suffix.log" -RedirectStandardError "target/history-http-$suffix.err.log"
  try { Wait-Ready 'http://127.0.0.1:18083/actuator/health' $process }
  catch {
    if (!$process.HasExited) { $process.Kill($true); $null = $process.WaitForExit(5000) }
    throw
  }
  return $process
}
try {
  Set-Location -LiteralPath $backendRoot
  foreach ($key in $settings.Keys) {
    $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process')
  }
  $mock = Start-Process -FilePath (Get-Command node).Source -WindowStyle Hidden -PassThru `
    -ArgumentList @('scripts/run-fusionpbx-mock.cjs') `
    -RedirectStandardOutput target/history-http-mock.log -RedirectStandardError target/history-http-mock.err.log
  $processes += $mock
  Wait-Ready 'http://127.0.0.1:14011/__control' $mock
  $server = Start-Backend 'first'
  $processes += $server
  $username = 'hist_http_' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
  $registration = @{ username = $username; email = "$username@example.test"; displayName = 'History HTTP'; password = 'verification-password' }
  $null = Invoke-RestMethod http://127.0.0.1:18083/api/v1/auth/register -Method Post -ContentType application/json -Body ($registration | ConvertTo-Json)
  $loginBody = @{ username = $username; password = 'verification-password' } | ConvertTo-Json
  $session = Invoke-RestMethod http://127.0.0.1:18083/api/v1/auth/login -Method Post -ContentType application/json -Body $loginBody
  postman collection run postman/collections/auth-users -i 'Save Message History' -i 'History Conversations' -i 'History Messages' `
    --env-var 'base_url=http://127.0.0.1:18083' --env-var "access_token=$($session.accessToken)" --no-report-events
  if ($LASTEXITCODE -ne 0) { throw 'History Postman requests failed.' }

  $recipientName = "${username}_b"
  $recipientRegistration = @{
    username = $recipientName; email = "$recipientName@example.test"; displayName = 'Offline Recipient'; password = 'verification-password'
  } | ConvertTo-Json
  $recipient = Invoke-RestMethod http://127.0.0.1:18083/api/v1/auth/register -Method Post -ContentType application/json -Body $recipientRegistration
  $recipientPeer = "sip:$($recipient.sip.extension)@$($recipient.sip.domain)"
  $senderPeer = "sip:$($session.user.sip.extension)@$($session.user.sip.domain)"
  postman collection run postman/collections/auth-users -i 'Send Direct Message' `
    --env-var 'base_url=http://127.0.0.1:18083' --env-var "access_token=$($session.accessToken)" `
    --env-var 'direct_message_id=offline-verification' --env-var "direct_message_peer=$recipientPeer" --no-report-events
  if ($LASTEXITCODE -ne 0) { throw 'Direct message Postman request failed.' }

  $server.Kill($true)
  $null = $server.WaitForExit(5000)
  $mock.Kill($true)
  $null = $mock.WaitForExit(5000)
  $server = Start-Backend 'restarted'
  $processes += $server
  $session = Invoke-RestMethod http://127.0.0.1:18083/api/v1/auth/login -Method Post -ContentType application/json -Body $loginBody
  $history = Invoke-RestMethod 'http://127.0.0.1:18083/api/v1/message-history/messages?peer=sip%3A1002%40pbx.test' -Headers @{ Authorization = "Bearer $($session.accessToken)" }
  if ($history.content.Count -ne 1 -or $history.content[0].id -ne 'manual-message-1' -or $history.content[0].body -ne 'Xin chào 👋') {
    throw 'Saved history was not restored after backend restart.'
  }
  Write-Output 'PASS persisted Unicode history survived executable backend restart and a fresh login.'

  $recipientLogin = @{ username = $recipientName; password = 'verification-password' } | ConvertTo-Json
  $recipientSession = Invoke-RestMethod http://127.0.0.1:18083/api/v1/auth/login -Method Post -ContentType application/json -Body $recipientLogin
  postman collection run postman/collections/auth-users -i 'History Updates' `
    --env-var 'base_url=http://127.0.0.1:18083' --env-var "access_token=$($recipientSession.accessToken)" --no-report-events
  if ($LASTEXITCODE -ne 0) { throw 'History updates Postman request failed.' }
  $recipientHistory = Invoke-RestMethod "http://127.0.0.1:18083/api/v1/message-history/messages?peer=$([Uri]::EscapeDataString($senderPeer))" `
    -Headers @{ Authorization = "Bearer $($recipientSession.accessToken)" }
  if ($recipientHistory.content.Count -ne 1 -or
      $recipientHistory.content[0].direction -ne 'incoming' -or
      $recipientHistory.content[0].body -ne 'Xin chào khi bạn mở lại app 👋') {
    throw 'The offline recipient did not receive exactly one stored message.'
  }
  Write-Output 'PASS offline recipient received the stored message on first login after backend restart.'
} finally {
  foreach ($process in $processes) { if (!$process.HasExited) { $process.Kill($true); $null = $process.WaitForExit(5000) } }
  foreach ($key in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key], 'Process') }
  Set-Location -LiteralPath $previousLocation
}
