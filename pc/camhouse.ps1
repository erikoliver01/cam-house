# Abre o CAM house sem janela de console: sobe o servidor escondido (se ainda nao estiver rodando),
# abre o app no Edge e, quando a janela do app fecha, encerra o servidor que ele mesmo subiu.
# Chamado pelo camhouse.vbs (atalho "CAM house" da Area de Trabalho e start.bat).
param([int]$Port = 8787)
$Dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$url = "http://127.0.0.1:$Port/"
# perfil proprio do Edge: a janela do app vira um processo separado do Edge normal
$perfil = Join-Path $env:LOCALAPPDATA 'CAMhouse\edge'

function NoAr { try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1', $Port); $c.Close(); $true } catch { $false } }
function EdgeDoApp { Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" | Where-Object { $_.CommandLine -like "*$perfil*" } }

$node = $null
if (-not (NoAr)) {
  New-Item -ItemType Directory -Force (Join-Path $Dir 'logs') | Out-Null
  $node = Start-Process 'C:\Program Files\nodejs\node.exe' -ArgumentList "`"$Dir\server.js`"" -WorkingDirectory $Dir `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput "$Dir\logs\server.log" -RedirectStandardError "$Dir\logs\server-erro.log"
  $ate = (Get-Date).AddSeconds(15)
  while (-not (NoAr) -and (Get-Date) -lt $ate -and -not $node.HasExited) { Start-Sleep -Milliseconds 300 }
}

Start-Process 'msedge' -ArgumentList "--app=$url", "--user-data-dir=`"$perfil`"", '--no-first-run', '--disable-background-mode'
Start-Sleep -Seconds 5
# espera todas as janelas do app fecharem (o Edge troca de processo; por isso olha o perfil, nao um PID)
while (EdgeDoApp) { Start-Sleep -Seconds 2 }

# so encerra o servidor que este lancador subiu; um servidor aberto de outro jeito continua
if ($node -and -not $node.HasExited) { Stop-Process -Id $node.Id }
