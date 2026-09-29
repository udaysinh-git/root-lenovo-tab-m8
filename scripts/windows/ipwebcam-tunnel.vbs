' Starts ipwebcam-tunnel.ps1 (next to this file) hidden. Put a shortcut to this file in shell:startup.
Set fso = CreateObject("Scripting.FileSystemObject")
script = fso.BuildPath(fso.GetParentFolderName(WScript.ScriptFullName), "ipwebcam-tunnel.ps1")
CreateObject("WScript.Shell").Run "pwsh.exe -NoProfile -ExecutionPolicy Bypass -File """ & script & """", 0, False
