# Compiles the builder window to Cricket07Builder.exe at the repository root, with the C# compiler Windows includes.
$ErrorActionPreference = "Stop"
$fx = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319"
$out = Join-Path (Split-Path $PSScriptRoot -Parent) "Cricket07Builder.exe"
$refs = "PresentationFramework.dll", "PresentationCore.dll", "WindowsBase.dll" | ForEach-Object { "/reference:" + (Join-Path $fx "WPF\$_") }
& (Join-Path $fx "csc.exe") /nologo /target:winexe /optimize+ "/out:$out" @refs /reference:System.Xaml.dll /reference:System.Windows.Forms.dll (Join-Path $PSScriptRoot "Cricket07Builder.cs")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "built $out"
