package com.pocketshell.app.smoke;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Visible marker checks across physical rows painted by the remote terminal. */
final class TerminalTextOracle {
    private static String matcher;

    private TerminalTextOracle() {}

    static String contains(String marker) throws IOException {
        // Read physical DOM rows: the buffer evidence already joins local soft
        // wraps, but aplexer's cursor-positioned redraws have no isWrapped flag.
        // Use the grid captured with the buffer, not the widest visible text.
        return "(() => {const grid=JSON.parse(window.__ps2857TerminalBufferState || '{}');"
                + "if (!(grid.cols > 0)) return false;"
                + "const rows=[...document.querySelectorAll('.xterm-rows')].find(n=>n.offsetParent!==null);"
                + "const screen=rows?.closest('.xterm-screen')?.getBoundingClientRect();"
                + "if (!rows || !screen || !(screen.width>0)) return false;"
                + "const physical=Array.from(rows.children);const cellWidth=screen.width/grid.cols;"
                + "const occupied=physical.map(row=>{const range=document.createRange();range.selectNodeContents(row);"
                + "const rects=Array.from(range.getClientRects()).filter(rect=>rect.width>0);"
                + "return rects.length?(Math.max(...rects.map(rect=>rect.right))-screen.left)/cellWidth:0;});"
                + "return (" + matcher() + ")(physical.map(row=>row.innerText).join('\\n'), " + JSONObject.quote(marker)
                + ", 'includes', grid.cols, occupied);})()";
    }

    private static synchronized String matcher() throws IOException {
        if (matcher == null) {
            try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                    .open("terminal-logical-lines.js")) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int count; (count = input.read(buffer)) > 0;) output.write(buffer, 0, count);
                matcher = new String(output.toByteArray(), StandardCharsets.UTF_8)
                        .replaceFirst("(?s)^/\\*.*?\\*/\\s*", "").trim();
            }
        }
        return matcher;
    }
}
