param()

$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    # Reuse workspace-local tools when present; otherwise use Android Studio/system tools.
    if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath '.build-tools/jdk')) {
        $loaderJdk = Get-ChildItem -LiteralPath '.build-tools/jdk' -Directory | Select-Object -First 1
        if ($loaderJdk) { $env:JAVA_HOME = $loaderJdk.FullName }
    }
    if (-not $env:ANDROID_HOME -and (Test-Path -LiteralPath '.build-tools/sdk')) {
        $env:ANDROID_HOME = (Resolve-Path -LiteralPath '.build-tools/sdk').Path
    }
    if (-not $env:GRADLE_USER_HOME -and (Test-Path -LiteralPath '.build-tools/gradle-home')) {
        $env:GRADLE_USER_HOME = (Resolve-Path -LiteralPath '.build-tools/gradle-home').Path
    }
    & .\gradlew.bat :protocol:test :app:testDebugUnitTest :app:assembleRelease :app:lintRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Android validation failed (exit $LASTEXITCODE)." }
    $loaderOutput = Join-Path $PSScriptRoot '../releases/android'
    New-Item -ItemType Directory -Path $loaderOutput -Force | Out-Null
    $loaderApk = Join-Path $loaderOutput 'TournaTest-Updater-beta.apk'
    Copy-Item -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Destination $loaderApk -Force
    $loaderHash = (Get-FileHash -LiteralPath $loaderApk -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$loaderApk.sha256" -Value "$loaderHash  TournaTest-Updater-beta.apk" -Encoding ascii
    Write-Host "Validated beta APK (debug signing key): $loaderApk"
} finally {
    Pop-Location
}
