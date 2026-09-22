$PSVersionTable.PSVersion.ToString()
foreach ($c in 'sh','bash','pwsh','powershell','cmd','go','node','python','python3','java','dotnet','elixir','clj','taskkill') {
  $g = Get-Command $c -ErrorAction SilentlyContinue
  if ($g) { "$c -> $($g.Source)" } else { "$c -> MISSING" }
}
"COMSPEC=$env:COMSPEC"
