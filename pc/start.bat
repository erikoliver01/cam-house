@echo off
rem Inicia o CAM house sem janela de console: o camhouse.vbs sobe o servidor escondido,
rem abre o app no Edge e fecha o servidor quando a janela do app fecha.
rem (versao antiga, com a janela "CamBox server": backup-visual\start.bat.bak-oculto)
start "" wscript.exe "%~dp0camhouse.vbs"
