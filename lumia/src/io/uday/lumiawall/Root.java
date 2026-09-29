package io.uday.lumiawall;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Root shell for the action center (Wi-Fi/Bluetooth toggles, brightness, charge control, reboot): Android 11 no
 * longer lets ordinary apps do these. Lumia Wall's uid is pre-granted in Magisk, so there's no prompt.
 * Always call off the UI thread.
 */
final class Root {
    private Root() {}

    /** Shared with the Magisk service.d scripts: a flag file here changes their behaviour. */
    static final String FLAGS = "/data/local/tmp/wall";

    static String run(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            if (!p.waitFor(8, TimeUnit.SECONDS)) p.destroy();
            return bo.toString("UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
