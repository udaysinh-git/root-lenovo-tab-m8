' Starts close-spacedesk-nag.ps1 (next to this file) with no console window at all (window style 0 = hidden).
' Put a shortcut to this file in shell:startup to run it at every sign-in.
Set fso = CreateObject("Scripting.FileSystemObject")
script = fso.BuildPath(fso.GetParentFolderName(WScript.ScriptFullName), "close-spacedesk-nag.ps1")
CreateObject("WScript.Shell").Run "pwsh.exe -NoProfile -ExecutionPolicy Bypass -File """ & script & """", 0, False
