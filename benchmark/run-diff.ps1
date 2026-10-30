param(
    [string]$CsvPath = "sample-data\generated-200k.csv",
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Token = $env:ROSTER_HR_TOKEN
)

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "Set ROSTER_HR_TOKEN to a short-lived HR source token."
}
if (-not (Test-Path -LiteralPath $CsvPath)) {
    throw "Snapshot file not found: $CsvPath. Run benchmark\generate-snapshot.py first."
}

$cutoff = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
$timer = [Diagnostics.Stopwatch]::StartNew()
$response = & curl.exe --fail-with-body --silent --show-error `
    -H "Authorization: Bearer $Token" `
    -F "sourceSystem=benchmark" `
    -F "snapshotCutoff=$cutoff" `
    -F "file=@$CsvPath;type=text/csv" `
    "$BaseUrl/api/v1/hr/snapshots"
$curlExitCode = $LASTEXITCODE
$timer.Stop()

if ($curlExitCode -ne 0) {
    throw "Snapshot request failed with curl exit code $curlExitCode."
}

$run = $response | ConvertFrom-Json
Write-Output "runId=$($run.runId)"
Write-Output "state=$($run.state)"
Write-Output "rows=$($run.uploadedRowCount)"
Write-Output "missingEmployees=$($run.missingEmployeeCount)"
Write-Output "client_upload_and_reconciliation_seconds=$([Math]::Round($timer.Elapsed.TotalSeconds, 3))"
Write-Output "machine_os=$([System.Environment]::OSVersion.VersionString)"
Write-Output "machine_logical_cores=$([Environment]::ProcessorCount)"
Write-Output "peak_heap_bytes=record separately with the documented Docker/JVM monitoring command"
