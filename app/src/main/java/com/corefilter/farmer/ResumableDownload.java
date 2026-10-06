package com.corefilter.farmer;

import java.io.*;
import java.net.HttpURLConnection;
import java.util.Locale;
import java.util.regex.*;

/** Bounded reconnects; durable byte offsets, including cancellation/process restarts. */
final class ResumableDownload {
    interface Source { HttpURLConnection open(long offset) throws Exception; }
    interface Monitor {
        void check() throws InterruptedIOException;
        void progress(String message);
    }
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    static void fetch(File part, long total, Source source, Monitor monitor) throws Exception {
        if (total <= 0 || total > ReleasePolicy.MAX_APK_BYTES) throw new IOException("Invalid APK size.");
        if (part.length() > total && !part.delete()) throw new IOException("Cannot reset the partial download.");
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            monitor.check();
            long offset = part.length();
            if (offset == total) return;
            HttpURLConnection connection = null;
            try {
                connection = source.open(offset);
                int status = connection.getResponseCode();
                long responseEnd = total;
                if (status == HttpURLConnection.HTTP_PARTIAL) {
                    String value = connection.getHeaderField("Content-Range");
                    Matcher match = RANGE.matcher(value == null ? "" : value);
                    if (!match.matches() || Long.parseLong(match.group(1)) != offset ||
                            Long.parseLong(match.group(3)) != total ||
                            Long.parseLong(match.group(2)) < offset || Long.parseLong(match.group(2)) >= total)
                        throw new FatalDownload("The server returned an invalid resume offset. Nothing was installed.");
                    responseEnd = Long.parseLong(match.group(2)) + 1;
                } else if (status == HttpURLConnection.HTTP_OK) {
                    // Servers may ignore Range. Truncate before accepting their complete response.
                    offset = 0;
                } else {
                    if (status >= 400 && status < 500 && status != 408 && status != 429)
                        throw new FatalDownload("GitHub download failed (" + status + "). Check for updates again.");
                    throw new IOException("GitHub download failed (" + status + ").");
                }
                long received = offset, started = System.nanoTime(), lastProgress = 0;
                try (InputStream input = connection.getInputStream();
                     OutputStream output = new BufferedOutputStream(new FileOutputStream(part, offset > 0), 131072)) {
                    monitor.progress(format(received, total, 0));
                    byte[] buffer = new byte[131072];
                    int count;
                    while (true) {
                        monitor.check();
                        count = input.read(buffer);
                        monitor.check();
                        if (count == -1) break;
                        if (received + count > responseEnd) throw new FatalDownload("The APK exceeded its release size.");
                        output.write(buffer, 0, count);
                        received += count;
                        long now = System.nanoTime();
                        if (now - lastProgress >= 500_000_000L) {
                            output.flush(); // Saved progress is a usable Range offset, not just a UI number.
                            lastProgress = now;
                            double speed = (received - offset) / Math.max(0.001, (now - started) / 1e9);
                            monitor.progress(format(received, total, speed));
                        }
                    }
                }
                if (received == total) return;
                throw new EOFException("Connection ended before the APK was complete.");
            } catch (FatalDownload ex) {
                throw ex;
            } catch (IOException ex) {
                monitor.check();
                last = ex;
                if (attempt < 3) monitor.progress("Reconnecting · saved " + String.format(Locale.ROOT, "%.1f / %.1f MB", part.length()/1048576.0, total/1048576.0));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new IOException("Download paused after connection problems. " +
                "Your progress is saved; tap Retry to continue.", last);
    }
    private static String format(long received, long total, double speed) {
        return String.format(Locale.ROOT, "Downloading · %d%%\n%.1f / %.1f MB%s", received*100/total,
                received/1048576.0, total/1048576.0,
                speed <= 0 ? "" : String.format(Locale.ROOT, " · %.1f MB/s", speed/1048576.0));
    }
    private static final class FatalDownload extends IOException {
        FatalDownload(String message) { super(message); }
    }
}
