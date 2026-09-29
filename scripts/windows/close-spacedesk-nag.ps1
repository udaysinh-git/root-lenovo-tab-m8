# Closes spacedesk's "Non-Commercial Viewer connected" notice as soon as it appears.
# Runs hidden at logon via the scheduled task "Close spacedesk notice".
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class Nag {
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr FindWindow(string cls, string title);
    [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
    public static bool CloseIfOpen(string title) {
        IntPtr h = FindWindow(null, title);
        if (h == IntPtr.Zero) return false;
        PostMessage(h, 0x0010, IntPtr.Zero, IntPtr.Zero);   // WM_CLOSE
        return true;
    }
}
'@

while ($true) {
    [void][Nag]::CloseIfOpen('spacedesk Non-Commercial Viewer connected')
    Start-Sleep -Seconds 2
}
