[CmdletBinding()]
param(
  [Parameter(Mandatory)]
  [string]$AssetPath,
  [Parameter(Mandatory)]
  [long]$ReleaseId,
  [string]$Repository = 'bakahuiii/SELENE'
)

$ErrorActionPreference = 'Stop'
$asset = Get-Item -LiteralPath $AssetPath
$credentialLines = & cmd.exe /d /s /c 'echo url=https://github.com| git credential fill'
if ($LASTEXITCODE -ne 0) { throw 'Git Credential Manager did not return a credential.' }
$tokenLine = $credentialLines | Where-Object { $_ -like 'password=*' } | Select-Object -First 1
if (-not $tokenLine) { throw 'No GitHub token was returned by Git Credential Manager.' }

$headers = @{
  Authorization = "Bearer $($tokenLine.Substring('password='.Length))"
  Accept = 'application/vnd.github+json'
  'X-GitHub-Api-Version' = '2022-11-28'
  'User-Agent' = 'SELENE-release'
}
$name = [Uri]::EscapeDataString($asset.Name)
$uri = "https://uploads.github.com/repos/$Repository/releases/$ReleaseId/assets?name=$name"
$response = Invoke-WebRequest -UseBasicParsing -Method Post -Headers $headers -ContentType 'application/octet-stream' -InFile $asset.FullName -Uri $uri
if ($response.StatusCode -ne 201) { throw "GitHub returned HTTP $($response.StatusCode)." }
Write-Output "Uploaded $($asset.Name) to release $ReleaseId."
