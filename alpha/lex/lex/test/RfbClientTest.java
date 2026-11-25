package org.lambdaproject.lambda;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Host-only tests for LexRfbClient: decoder units plus one end-to-end run
 *  against fake_rfb_server.py. Not part of the APK build. */
public final class RfbClientTest {
    public static void main(String[] args) throws Exception {
        desKnownAnswer();
        keysymMapping();
        rawDecode();
        hextileUnits();
        copyRectOverlap();
        if (args.length < 2) throw new IllegalArgumentException("usage: RfbClientTest <port> <report>");
        endToEnd(Integer.parseInt(args[0]), args[1]);
        System.out.println("PASS: LexRfbClient host tests");
    }

    private static void desKnownAnswer() throws Exception {
        byte[] challenge = new byte[16];
        for (int i = 0; i < challenge.length; i++) challenge[i] = (byte) i;
        String response = hex(LexRfbClient.vncResponse(challenge, "01234567"));
        check("VncAuth DES known-answer vector",
            response.equals("e78701495117e1afd62fb5d65bb98b20"));
    }

    private static void keysymMapping() {
        check("keysym latin-1", LexRfbClient.keysym('a') == 0x61);
        check("keysym return", LexRfbClient.keysym('\n') == LexRfbClient.KEY_RETURN);
        check("keysym backspace", LexRfbClient.keysym('\b') == LexRfbClient.KEY_BACKSPACE);
        check("keysym unicode", LexRfbClient.keysym(0x20ac) == 0x010020ac);
        check("modifier keysyms", LexRfbClient.KEY_SHIFT == 0xffe1
            && LexRfbClient.KEY_CONTROL == 0xffe3 && LexRfbClient.KEY_ALT == 0xffe9
            && LexRfbClient.KEY_SUPER == 0xffeb);
    }

    private static void rawDecode() throws Exception {
        int[] frame = new int[2 * 2];
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(pixel(0xff0000));
        bytes.write(pixel(0x00ff00));
        bytes.write(pixel(0x0000ff));
        bytes.write(pixel(0xffffff));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        LexRfbClient.decodeRaw(in, 0, 0, 2, 2, frame, 2, new byte[2 * 4]);
        check("raw decode + alpha", frame[0] == 0xffff0000 && frame[1] == 0xff00ff00
            && frame[2] == 0xff0000ff && frame[3] == 0xffffffff);
    }

    private static void hextileUnits() throws Exception {
        int[] frame = new int[32 * 16];
        byte[] row = new byte[32 * 4];
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(2);
        bytes.write(pixel(0xff0000));                 // tile 0: solid red
        bytes.write(1);
        for (int i = 0; i < 256; i++) bytes.write(pixel(0x00ff00)); // tile 1: raw green
        decodeHextile(bytes, frame, row);
        check("hextile solid tile", frame[0] == 0xffff0000 && frame[15] == 0xffff0000);
        check("hextile raw tile", frame[16] == 0xff00ff00 && frame[31] == 0xff00ff00);

        frame = new int[32 * 16];
        bytes = new ByteArrayOutputStream();
        bytes.write(2 | 8 | 16);
        bytes.write(pixel(0x0000ff));
        bytes.write(1);
        bytes.write(pixel(0xffff00));
        bytes.write(0x00);
        bytes.write(0xff);                            // tile 0: yellow full rect
        bytes.write(2 | 4 | 8);
        bytes.write(pixel(0xff0000));
        bytes.write(pixel(0xffffff));
        bytes.write(1);
        bytes.write(0x00);
        bytes.write(0xff);                            // tile 1: white full rect
        decodeHextile(bytes, frame, row);
        check("hextile coloured subrect", frame[0] == 0xffffff00);
        check("hextile uncoloured subrect", frame[16] == 0xffffffff);

        frame = new int[32 * 16];
        bytes = new ByteArrayOutputStream();
        bytes.write(2);
        bytes.write(pixel(0xff00ff));                 // tile 0: magenta background
        bytes.write(0);                               // tile 1: background persists
        decodeHextile(bytes, frame, row);
        check("hextile background persistence", frame[0] == 0xffff00ff && frame[16] == 0xffff00ff);
    }

    private static void decodeHextile(ByteArrayOutputStream bytes, int[] frame, byte[] row) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        LexRfbClient.decodeHextile(in, 0, 0, 32, 16, frame, 32, row);
    }

    private static void copyRectOverlap() throws Exception {
        int[] frame = new int[3 * 4];
        for (int i = 0; i < frame.length; i++) frame[i] = i;
        byte[] source = {0, 0, 0, 0};                 // src x=0, y=0
        LexRfbClient.copyRect(new DataInputStream(new ByteArrayInputStream(source)),
            0, 1, 3, 3, frame, 3, 3, 4);
        check("copyrect overlapping rows",
            frame[0] == 0 && frame[3] == 0 && frame[6] == 3 && frame[9] == 6);

        frame = new int[3 * 4];
        for (int i = 0; i < frame.length; i++) frame[i] = i;
        source = new byte[]{0, 0, 0, 1};              // src x=0, y=1
        LexRfbClient.copyRect(new DataInputStream(new ByteArrayInputStream(source)),
            0, 0, 3, 3, frame, 3, 3, 4);
        check("copyrect rows upward",
            frame[0] == 3 && frame[3] == 6 && frame[6] == 9 && frame[9] == 9);
    }

    private static void endToEnd(int port, String reportPath) throws Exception {
        final int[] expected = {
            0xffff0000, 0xff00ff00, 0xffffff00, 0xffffffff, // row of tiles at y=0
        };
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch update = new CountDownLatch(1);
        final AtomicReference<int[]> frame = new AtomicReference<int[]>();
        final AtomicReference<String> failure = new AtomicReference<String>();
        LexRfbClient client = new LexRfbClient();
        client.connect("127.0.0.1", port, "vnc-test", new LexRfbClient.Listener(){
            @Override public void onReady(int width, int height, int[] pixels) {
                if (width != 64 || height != 48 || pixels.length != 64 * 48) {
                    failure.set("server init " + width + "x" + height);
                }
                frame.set(pixels);
                ready.countDown();
            }
            @Override public void onFramebufferUpdate(int[] pixels, int[] damage, int count) {
                if (count < 4) failure.set("empty damage list");
                update.countDown();
            }
            @Override public void onError(String detail) {
                failure.set("client error: " + detail);
            }
        });
        check("server init within 4s", ready.await(4, TimeUnit.SECONDS));
        check("hextile update within 4s", update.await(4, TimeUnit.SECONDS));
        check("no client error", failure.get() == null);
        int[] pixels = frame.get();
        check("update damage covers framebuffer", pixels != null);
        check("pixel red", pixels[0] == expected[0]);
        check("pixel raw green", pixels[16] == expected[1]);
        check("pixel coloured subrect", pixels[40] == expected[2]);
        check("pixel uncoloured subrect", pixels[60] == expected[3]);
        check("pixel background persistence", pixels[20 * 64 + 20] == 0xffff00ff);
        check("pixel coloured subrect interior", pixels[35 * 64 + 19] == 0xff00ffff);
        check("pixel uncoloured subrect interior", pixels[41 * 64 + 41] == 0xff00ff00);
        boolean opaque = true;
        for (int value : pixels) if ((value >>> 24) != 0xff) { opaque = false; break; }
        check("ARGB_8888 alpha is 0xFF", opaque);

        client.pointer(10, 20, 1);
        client.pointer(10, 20, 0);
        client.key(0x41, true);
        client.key(0x41, false);
        client.scroll(1);
        // Input and the incremental request travel through the writer queue:
        // give the writer a moment before closing, or close() drops them.
        Thread.sleep(300);
        client.close();

        Path report = Paths.get(reportPath);
        String json = null;
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(report)) {
                json = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
                if (json.contains("\"fb_requests\":[[0,0,0,64,48],[1,0,0,64,48]]")) break;
            }
            Thread.sleep(50);
        }
        check("server report written", json != null && json.contains("\"fb_requests\""));
        check("VncAuth accepted", json.contains("\"auth_ok\":true"));
        check("SetPixelFormat 32bpp LE true-colour",
            json.contains("\"pixel_format\":\"2018000100ff00ff00ff100800000000\""));
        check("SetEncodings Hextile/CopyRect/Raw",
            json.contains("\"encodings\":[5,1,0]"));
        check("full then incremental request, one in flight",
            json.contains("\"fb_requests\":[[0,0,0,64,48],[1,0,0,64,48]]"));
        check("PointerEvent bytes",
            json.contains("\"pointer_events\":[\"0501000a0014\",\"0500000a0014\","
                + "\"0510000a0014\",\"0500000a0014\"]"));
        check("KeyEvent bytes",
            json.contains("\"key_events\":[\"0401000000000041\",\"0400000000000041\"]"));
    }

    private static byte[] pixel(int rgb) {
        int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
        return new byte[]{(byte) b, (byte) g, (byte) r, 0};
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02x", value & 0xff));
        return text.toString();
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            System.out.println("FAIL: " + name);
            throw new AssertionError(name);
        }
        System.out.println("PASS: " + name);
    }
}
