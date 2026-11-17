<#
    Type-check ONE package tree of the Java rewrite without touching target/.

    Usage (from the repo root):
        pwsh -File server/tools/check-package.ps1 -Path server/src/main/java/us/minitv/account

    Compiles the given sources together with everything already in target/classes
    (the shared foundation) into a throw-away directory. Good enough to catch
    resolving/syntax errors while several agents work in parallel; it does not
    replace the Lead's full `mvn compile`.
#>
param(
    [Parameter(Mandatory = $true)][string]$Path
)

$ErrorActionPreference = "Stop"
# server/tools -> server
$root = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $root "target\classes"
$depsFile = Join-Path $root "deps-cp.txt"
if (-not (Test-Path $depsFile)) {
    throw "missing $depsFile - run: mvn -q dependency:build-classpath -Dmdep.outputFile=deps-cp.txt"
}
$deps = (Get-Content $depsFile -Raw).Trim()
$out = Join-Path $env:TEMP ("javac-check-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $out | Out-Null

$files = @(Get-ChildItem -Path $Path -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($files.Count -eq 0) {
    throw "no .java files under $Path"
}

& javac -encoding UTF-8 -nowarn -cp "$classes;$deps" -d $out -sourcepath "$root\src\main\java" $files
if ($LASTEXITCODE -ne 0) {
    Write-Output "JAVAC_FAILED"
    exit 1
}
Write-Output "JAVAC_OK ($($files.Count) files)"
