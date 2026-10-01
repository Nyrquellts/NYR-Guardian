import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes an animated GIF from PNG frames with frame differencing and lossy LZW, for crowded scenes that ffmpeg cannot get
 * under BuiltByBit's size limit without crushing colours. Run with the JDK's single-file launcher:
 *
 * <pre>java -Xmx1g LossyGif.java frames=DIR count=N palette=palette.png out=OUT.gif fps-in=20 fps=12.5 hold-ms=900 lossy=16
 *     exact-above=96 exact-from=539</pre>
 *
 * Frames f0000.png .. f(count-1).png are resampled to fps; the last frame stays up hold-ms longer. The palette is ffmpeg
 * palettegen's 16x16 PNG with its last entry reserved for transparency. A pixel is left transparent (showing the previous
 * frame) while it is within the allowed levels of its palette colour, and the LZW encoder may extend a code with a
 * dictionary entry whose colour is within those levels instead of starting a new code. Allowed: lossy, a third of it on
 * dark colours, and none in rows above exact-above or from exact-from on (captions and chat), so text never ghosts.
 */
public final class LossyGif {

    private static final int TRANSPARENT = 255;
    private static final int MAX_BITS = 12;
    private static final int MAX_CODES = 1 << MAX_BITS;
    private static final int CLEAR = 256;
    private static final int EOI = 257;

    public static void main(String[] args) throws IOException {
        Map<String, String> opt = new HashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            opt.put(arg.substring(0, eq), arg.substring(eq + 1));
        }
        Path frames = Path.of(opt.get("frames"));
        int count = Integer.parseInt(opt.get("count"));
        double fpsIn = Double.parseDouble(opt.getOrDefault("fps-in", "20"));
        double fps = Double.parseDouble(opt.getOrDefault("fps", "20"));
        int holdMs = Integer.parseInt(opt.getOrDefault("hold-ms", "0"));
        int lossy = Integer.parseInt(opt.getOrDefault("lossy", "0"));
        int exactAbove = Integer.parseInt(opt.getOrDefault("exact-above", "0"));
        int exactFrom = Integer.parseInt(opt.getOrDefault("exact-from", String.valueOf(Integer.MAX_VALUE)));
        int[] palette = readPalette(Path.of(opt.get("palette")));

        List<Integer> picks = new ArrayList<>();
        for (int k = 0; ; k++) {
            int src = (int) Math.round(k * fpsIn / fps);
            if (src >= count) break;
            if (picks.isEmpty() || picks.get(picks.size() - 1) != src) picks.add(src);
        }
        if (picks.get(picks.size() - 1) != count - 1) picks.add(count - 1);

        BufferedImage first = ImageIO.read(frames.resolve(name(0)).toFile());
        int w = first.getWidth();
        int h = first.getHeight();
        Encoder enc = new Encoder(w, h, palette, lossy, exactAbove, exactFrom);
        ByteArrayOutputStream gif = new ByteArrayOutputStream();
        header(gif, w, h, palette);

        byte[] pending = null;
        long pendingStartMs = 0;
        long shownUntilCs = 0;
        for (int i = 0; i < picks.size(); i++) {
            int src = picks.get(i);
            long startMs = Math.round(src * 1000 / fpsIn);
            int[] target = ImageIO.read(frames.resolve(name(src)).toFile()).getRGB(0, 0, w, h, null, 0, w);
            byte[] block = enc.frame(target, i == 0);
            if (block == null) continue; // nothing visible changed: the previous frame simply stays up longer
            if (pending != null) {
                long endCs = Math.round(startMs / 10.0);
                write(gif, pending, (int) (endCs - shownUntilCs));
                shownUntilCs = endCs;
            }
            pending = block;
            pendingStartMs = startMs;
        }
        long endMs = Math.round(picks.get(picks.size() - 1) * 1000 / fpsIn + 1000 / fpsIn) + holdMs;
        write(gif, pending, (int) (Math.round(endMs / 10.0) - shownUntilCs));
        gif.write(0x3B);
        Files.write(Path.of(opt.get("out")), gif.toByteArray());
        System.out.printf("%s: %d frames written, %.2f MB, max error %d, mean error %.2f%n", opt.get("out"),
                enc.framesWritten, gif.size() / 1e6, enc.maxError, enc.errorSum / (double) Math.max(1, enc.pixelsShown));
    }

    private static String name(int i) {
        return String.format("f%04d.png", i);
    }

    private static int[] readPalette(Path png) throws IOException {
        BufferedImage img = ImageIO.read(png.toFile());
        int[] out = new int[256];
        for (int i = 0; i < 256; i++) out[i] = img.getRGB(i % img.getWidth(), i / img.getWidth()) & 0xFFFFFF;
        return out;
    }

    private static void header(OutputStream out, int w, int h, int[] palette) throws IOException {
        out.write("GIF89a".getBytes());
        le16(out, w);
        le16(out, h);
        out.write(0xF7); // global colour table of 256 entries
        out.write(0);
        out.write(0);
        for (int rgb : palette) {
            out.write(rgb >> 16 & 0xFF);
            out.write(rgb >> 8 & 0xFF);
            out.write(rgb & 0xFF);
        }
        out.write(new byte[]{0x21, (byte) 0xFF, 0x0B});
        out.write("NETSCAPE2.0".getBytes());
        out.write(new byte[]{0x03, 0x01, 0x00, 0x00, 0x00}); // loop forever
    }

    private static void write(OutputStream out, byte[] block, int delayCs) throws IOException {
        // Graphic control: keep the previous frame under this one, index 255 transparent.
        out.write(new byte[]{0x21, (byte) 0xF9, 0x04, 0x05});
        le16(out, Math.max(2, delayCs));
        out.write(new byte[]{(byte) TRANSPARENT, 0x00});
        out.write(block);
    }

    private static void le16(OutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write(v >> 8 & 0xFF);
    }

    private static int diff(int a, int b) {
        return Math.max(Math.abs((a >> 16 & 0xFF) - (b >> 16 & 0xFF)),
                Math.max(Math.abs((a >> 8 & 0xFF) - (b >> 8 & 0xFF)), Math.abs((a & 0xFF) - (b & 0xFF))));
    }

    /** Frame differencing and lossy LZW over one shared palette and one on-screen buffer. */
    private static final class Encoder {
        final int w, h, lossy, exactAbove, exactFrom;
        final int[] palette;
        final int[] shown;
        final int[] nearest = new int[1 << 24];
        long errorSum, pixelsShown;
        int maxError, framesWritten;

        // LZW dictionary: child[code * 256 + symbol], valid when stamp matches; children listed per code for lossy search.
        final int[] child = new int[MAX_CODES * 256];
        final int[] childStamp = new int[MAX_CODES * 256];
        final int[] firstChild = new int[MAX_CODES];
        final int[] firstStamp = new int[MAX_CODES];
        final int[] nextSibling = new int[MAX_CODES];
        final int[] symbolOf = new int[MAX_CODES];
        int generation = 1;

        Encoder(int w, int h, int[] palette, int lossy, int exactAbove, int exactFrom) {
            this.w = w;
            this.h = h;
            this.palette = palette;
            this.lossy = lossy;
            this.exactAbove = exactAbove;
            this.exactFrom = exactFrom;
            this.shown = new int[w * h];
            Arrays.fill(nearest, -1);
        }

        /**
         * How far a shown pixel may sit from its palette colour: nothing in the caption and chat bands, where an error shows
         * as speckles or ghost letters on a dark ground; a third of the level on dark colours; the full level elsewhere.
         */
        int limit(int p, int want) {
            int y = p / w;
            if (y < exactAbove || y >= exactFrom) return 0;
            int luma = (2 * (want >> 16 & 0xFF) + 5 * (want >> 8 & 0xFF) + (want & 0xFF)) / 8;
            return luma < 56 ? lossy / 3 : lossy;
        }

        int nearestIndex(int rgb) {
            int cached = nearest[rgb];
            if (cached >= 0) return cached;
            int best = 0;
            long bestD = Long.MAX_VALUE;
            for (int i = 0; i < TRANSPARENT; i++) {
                int p = palette[i];
                long dr = (rgb >> 16 & 0xFF) - (p >> 16 & 0xFF), dg = (rgb >> 8 & 0xFF) - (p >> 8 & 0xFF), db = (rgb & 0xFF) - (p & 0xFF);
                long d = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            nearest[rgb] = best;
            return best;
        }

        /** @return the image descriptor and data for this frame, or null when nothing on screen would change */
        byte[] frame(int[] argb, boolean first) {
            int[] target = new int[argb.length];
            int minX = w, minY = h, maxX = -1, maxY = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = y * w + x;
                    target[p] = argb[p] & 0xFFFFFF;
                    int want = palette[nearestIndex(target[p])];
                    if (first || diff(want, shown[p]) > limit(p, want)) {
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
            if (maxX < 0) return null;
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x2C);
            out.write(minX & 0xFF);
            out.write(minX >> 8);
            out.write(minY & 0xFF);
            out.write(minY >> 8);
            out.write(bw & 0xFF);
            out.write(bw >> 8);
            out.write(bh & 0xFF);
            out.write(bh >> 8);
            out.write(0);
            out.write(8);
            lzw(out, target, first, minX, minY, bw, bh);
            framesWritten++;
            return out.toByteArray();
        }

        /**
         * The symbol an exact encoder would write: transparent while the screen already shows the pixel's palette colour, or
         * one within lossy levels of it. Distances are measured from that palette colour, the best the GIF can show.
         */
        int exact(int p, int t, boolean first) {
            int best = nearestIndex(t);
            if (!first && diff(palette[best], shown[p]) <= limit(p, palette[best])) return TRANSPARENT;
            return best;
        }

        /** How far the screen would be from the pixel's palette colour if this symbol were written, or -1 if too far. */
        int cost(int p, int t, int symbol, boolean first) {
            int want = palette[nearestIndex(t)];
            int limit = limit(p, want);
            if (symbol == TRANSPARENT) {
                if (first) return -1;
                int d = diff(want, shown[p]);
                return d <= limit ? d : -1;
            }
            int d = diff(want, palette[symbol]);
            return d <= limit ? d : -1;
        }

        void show(int p, int t, int symbol) {
            if (symbol != TRANSPARENT) shown[p] = palette[symbol];
            int e = diff(t, shown[p]);
            errorSum += e;
            pixelsShown++;
            maxError = Math.max(maxError, e);
        }

        int lookup(int code, int symbol) {
            int k = code * 256 + symbol;
            return childStamp[k] == generation ? child[k] : -1;
        }

        void add(int code, int symbol, int newCode) {
            int k = code * 256 + symbol;
            child[k] = newCode;
            childStamp[k] = generation;
            symbolOf[newCode] = symbol;
            nextSibling[newCode] = firstStamp[code] == generation ? firstChild[code] : -1;
            firstChild[code] = newCode;
            firstStamp[code] = generation;
        }

        // Bit packing and code widths follow the classic GIF LZW encoder (Kevin Weiner's LZWEncoder, after compress).
        int nBits, maxCode, freeEnt, curBits;
        long curAccum;
        boolean clearFlag;
        ByteArrayOutputStream sub;

        void lzw(ByteArrayOutputStream out, int[] target, boolean first, int x0, int y0, int bw, int bh) {
            sub = new ByteArrayOutputStream();
            nBits = 9;
            maxCode = (1 << nBits) - 1;
            freeEnt = CLEAR + 2;
            curBits = 0;
            curAccum = 0;
            clearFlag = false;
            generation++;
            output(CLEAR);
            int p0 = y0 * w + x0;
            int ent = exact(p0, target[p0], first);
            show(p0, target[p0], ent);
            for (int y = y0; y < y0 + bh; y++) {
                for (int x = (y == y0 ? x0 + 1 : x0); x < x0 + bw; x++) {
                    int p = y * w + x;
                    int t = target[p];
                    int s = exact(p, t, first);
                    int next = lookup(ent, s);
                    if (next >= 0) {
                        ent = next;
                        show(p, t, s);
                        continue;
                    }
                    if (lossy > 0 && firstStamp[ent] == generation) {
                        int bestCode = -1, bestSym = -1, bestCost = Integer.MAX_VALUE;
                        for (int c = firstChild[ent]; c >= 0; c = nextSibling[c]) {
                            int cost = cost(p, t, symbolOf[c], first);
                            if (cost >= 0 && cost < bestCost) {
                                bestCost = cost;
                                bestCode = c;
                                bestSym = symbolOf[c];
                            }
                        }
                        if (bestCode >= 0) {
                            ent = bestCode;
                            show(p, t, bestSym);
                            continue;
                        }
                    }
                    output(ent);
                    if (freeEnt < MAX_CODES) {
                        add(ent, s, freeEnt++);
                    } else {
                        generation++;
                        freeEnt = CLEAR + 2;
                        clearFlag = true;
                        output(CLEAR);
                    }
                    ent = s;
                    show(p, t, s);
                }
            }
            output(ent);
            output(EOI);
            byte[] data = sub.toByteArray();
            for (int i = 0; i < data.length; i += 255) {
                int n = Math.min(255, data.length - i);
                out.write(n);
                out.write(data, i, n);
            }
            out.write(0);
        }

        void output(int code) {
            curAccum &= (1L << curBits) - 1;
            curAccum |= (long) code << curBits;
            curBits += nBits;
            while (curBits >= 8) {
                sub.write((int) (curAccum & 0xFF));
                curAccum >>= 8;
                curBits -= 8;
            }
            if (freeEnt > maxCode || clearFlag) {
                if (clearFlag) {
                    nBits = 9;
                    maxCode = (1 << nBits) - 1;
                    clearFlag = false;
                } else {
                    nBits++;
                    maxCode = nBits == MAX_BITS ? MAX_CODES : (1 << nBits) - 1;
                }
            }
            if (code == EOI) {
                while (curBits > 0) {
                    sub.write((int) (curAccum & 0xFF));
                    curAccum >>= 8;
                    curBits -= 8;
                }
                curBits = 0;
            }
        }
    }
}
