' Abre o CAM house sem nenhuma janela de console (chama o camhouse.ps1 escondido).
Set fso = CreateObject("Scripting.FileSystemObject")
pasta = fso.GetParentFolderName(WScript.ScriptFullName)
args = ""
If WScript.Arguments.Count > 0 Then args = " -Port " & WScript.Arguments(0)
CreateObject("WScript.Shell").Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & pasta & "\camhouse.ps1""" & args, 0, False
