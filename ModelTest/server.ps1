$projectPath = "C:\Users\mikel\gemini-web-to-api"
$envPath = Join-Path $projectPath ".env"

if (-not (Test-Path -LiteralPath $envPath)) {
	throw "No se encontró el archivo .env en $projectPath"
}

foreach ($line in Get-Content -LiteralPath $envPath) {
	if ($line -match '^\s*([^#=]+?)\s*=\s*(.*)\s*$') {
		$name = $matches[1].Trim()
		$value = $matches[2].Trim()
		if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
			$value = $value.Substring(1, $value.Length - 2)
		}
		[Environment]::SetEnvironmentVariable($name, $value, "Process")
	}
}

Set-Location -LiteralPath $projectPath
& go run ./cmd/server