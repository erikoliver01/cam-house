# Gera o CAMhouse.apk (video e controle direto da camera; instala por cima do CAM house antigo) sem Gradle, so com o SDK do Android que ja esta no PC.
# Uso: powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Continue'   # ferramentas escrevem avisos no stderr; cada passo confere $LASTEXITCODE
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = "$sdk\build-tools\36.0.0"
$jar = "$sdk\platforms\android-35\android.jar"
$app = $PSScriptRoot
$out = "$app\build"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory "$out\gen", "$out\obj", "$out\dex" | Out-Null
# data da compilacao para a tela "Sobre" (R.string.data_build)
$data = Get-Date -Format 'dd/MM/yyyy HH:mm'
Set-Content -Encoding utf8 "$app\res\values\build.xml" "<?xml version=`"1.0`" encoding=`"utf-8`"?>`n<resources><string name=`"data_build`">$data</string></resources>"

& "$bt\aapt2.exe" compile --dir "$app\res" -o "$out\res.zip"; if ($LASTEXITCODE) { throw 'aapt2 compile' }
& "$bt\aapt2.exe" link -I $jar --manifest "$app\AndroidManifest.xml" -o "$out\base.apk" "$out\res.zip" `
  --java "$out\gen" --min-sdk-version 26 --target-sdk-version 34 --version-code 14 --version-name 1.3 --rename-manifest-package com.erik.camhouse; if ($LASTEXITCODE) { throw 'aapt2 link' }
$src = (Get-ChildItem "$app\src", "$out\gen" -Recurse -Filter *.java).FullName
& javac -nowarn --release 17 -classpath $jar -d "$out\obj" $src; if ($LASTEXITCODE) { throw 'javac' }
& "$bt\d8.bat" --lib $jar --min-api 26 --output "$out\dex" (Get-ChildItem "$out\obj" -Recurse -Filter *.class).FullName; if ($LASTEXITCODE) { throw 'd8' }
Copy-Item "$out\base.apk" "$out\unsigned.apk"
& jar -u -M -f "$out\unsigned.apk" -C "$out\dex" classes.dex; if ($LASTEXITCODE) { throw 'jar' }
# fontes do app: pelo jar, porque o aapt2 -A no Windows grava "assets/fonts\x.ttf" com barra invertida
if (Test-Path "$app\assets") { & jar -u -M -f "$out\unsigned.apk" -C "$app" assets; if ($LASTEXITCODE) { throw 'jar assets' } }
& "$bt\zipalign.exe" -p -f 4 "$out\unsigned.apk" "$out\aligned.apk"; if ($LASTEXITCODE) { throw 'zipalign' }
# shortcut: assina com a chave de debug do Android Studio deste PC; atualizar o app exige gerar sempre neste PC
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android `
  --out "$out\CAMhouse.apk" "$out\aligned.apk"; if ($LASTEXITCODE) { throw 'apksigner' }
& "$bt\apksigner.bat" verify "$out\CAMhouse.apk"; if ($LASTEXITCODE) { throw 'verify' }
Write-Host "OK: $out\CAMhouse.apk"
