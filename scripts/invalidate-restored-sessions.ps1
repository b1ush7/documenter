# Run while application instances are stopped, after restoring Redis and before accepting traffic.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$RedisCli,
    [string]$RedisHost = '127.0.0.1',
    [Parameter(Mandatory=$true)][ValidateRange(1,65535)][int]$Port,
    [ValidateRange(0,2147483647)][int]$Database = 0,
    [switch]$Apply
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $RedisCli -PathType Leaf)) { throw 'redis-cli executable is missing.' }
# Authentication, when required, is supplied through REDISCLI_AUTH, never a command-line password.
$cursor = '0'
$matched = 0
$deleted = 0
do {
    $scan = & $RedisCli -h $RedisHost -p $Port -n $Database --json SCAN $cursor MATCH 'login:*' COUNT 100
    if ($LASTEXITCODE -ne 0) { throw 'Redis SCAN failed.' }
    $page = ($scan -join "`n") | ConvertFrom-Json
    if ($page.Count -ne 2 -or [string]$page[0] -notmatch '^\d+$' -or $page[1] -isnot [System.Array]) {
        throw 'Unexpected Redis SCAN response; recovery stopped.'
    }
    $cursor = [string]$page[0]
    $keys = @($page[1] | Where-Object { $_ -match '^login:(session:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|user_sessions:[0-9]+)$' })
    $matched += $keys.Count
    if ($Apply -and $keys.Count -gt 0) {
        $removed = & $RedisCli -h $RedisHost -p $Port -n $Database --raw DEL @keys
        if ($LASTEXITCODE -ne 0 -or "$removed" -notmatch '^\d+$') { throw 'Redis session invalidation failed.' }
        $deleted += [long]$removed
    }
} while ($cursor -ne '0')
[ordered]@{ applied=[bool]$Apply; matched=$matched; deleted=$deleted } | ConvertTo-Json -Compress
