package com.pocketshell.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Production {@link GoogleSyncSession.Transport}. Reads the error stream on a
 * non-2xx status so the 404/409 bodies the sync flow branches on still arrive,
 * caps the response, and always disconnects.
 */
final class HttpSyncTransport implements GoogleSyncSession.Transport {
    private static final int TIMEOUT_MS = 15_000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    @Override
    public GoogleSyncSession.Response send(String method, String url, Map<String, String> headers, String body)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            return new GoogleSyncSession.Response(status, read(stream));
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_RESPONSE_BYTES) throw new IOException("sync response is too large");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
