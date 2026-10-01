package com.nyr.guardian.chunkhopper;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * plugins/NYR-ChunkHopper/sales.journal: every sale, forced to disk before its money is paid.
 *
 * <p>A sale takes the stacks out of the hopper and raises the hopper's sale number in its TileState in the same write, so
 * the world saves both or neither. The journal then records the sale (which hopper, its number, the stacks, the amount)
 * and only once that record is on disk is the owner paid. After a crash the world comes back as it was last saved: a
 * hopper whose saved sale number is below a recorded sale lost that sale's removal while its money may have been paid,
 * so the stacks are taken out again when its chunk loads. Money can be lost to a crash (a sale recorded but never paid);
 * it is never paid twice.</p>
 *
 * <p>Lines: {@code S hopper seq world x y z owner amount stacks} for a sale, {@code V hopper seq} for a sale the economy
 * refused (its stacks went back), {@code P hopper seq} for sales up to seq known to be in the saved world. Written by one
 * thread, in batches, each batch forced to disk.</p>
 */
final class SaleJournal {

    /** One recorded sale. */
    record Entry(UUID hopper, long seq, UUID world, int x, int y, int z, UUID owner, double amount, List<ItemStack> stacks, boolean voided) {

        Entry voidedCopy() {
            return new Entry(hopper, seq, world, x, y, z, owner, amount, stacks, true);
        }

        ChunkId chunk() {
            return new ChunkId(world, x >> 4, z >> 4);
        }

        int items() {
            int items = 0;
            for (ItemStack stack : stacks) {
                items += stack.getAmount();
            }
            return items;
        }
    }

    /** Entries kept per hopper at most; older ones are taken as saved (the server autosaves every few minutes). */
    static final int MAX_PER_HOPPER = 1_000;

    private final File file;
    private final Logger logger;
    private final Map<UUID, List<Entry>> live = new ConcurrentHashMap<>();
    /** Which hoppers have recorded sales in a chunk, so a chunk load is checked without a scan. */
    private final Map<ChunkId, Set<UUID>> byChunk = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicInteger deadLines = new AtomicInteger();
    private final ExecutorService writer;
    private FileChannel channel;
    private volatile boolean broken;
    private volatile boolean warnedCap;

    private record Pending(String line, CompletableFuture<Void> durable) {
    }

    SaleJournal(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "NYR-ChunkHopper-sales");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ------------------------------------------------------------------ opening

    /** Reads what an earlier run left (sales not yet known to be saved), rewrites the file with only those, and opens it. */
    void open() throws IOException {
        try {
            load();
        } catch (IOException | RuntimeException failed) {
            broken = true;
            throw failed instanceof IOException io ? io : new IOException(failed);
        }
    }

    private void load() throws IOException {
        live.clear();
        byChunk.clear();
        if (file.isFile()) {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                try {
                    replay(line);
                } catch (RuntimeException broken) {
                    // A line cut short by a crash is the last one; anything else unreadable is skipped and reported.
                    logger.warning("Skipped an unreadable line in " + file.getName() + " (" + broken.getMessage() + ")");
                }
            }
        }
        rewrite();
        channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    private void replay(String line) {
        if (line.isBlank()) {
            return;
        }
        String[] field = line.trim().split(" ");
        switch (field[0]) {
            case "S" -> add(new Entry(UUID.fromString(field[1]), Long.parseLong(field[2]), UUID.fromString(field[3]),
                Integer.parseInt(field[4]), Integer.parseInt(field[5]), Integer.parseInt(field[6]),
                UUID.fromString(field[7]), Double.parseDouble(field[8]), decode(field[9], field[10]), false));
            case "V" -> markVoided(UUID.fromString(field[1]), Long.parseLong(field[2]));
            case "P" -> dropUpTo(UUID.fromString(field[1]), Long.parseLong(field[2]));
            default -> throw new IllegalArgumentException("unknown record " + field[0]);
        }
    }

    // ------------------------------------------------------------------ recording (any thread)

    /** Records a sale; the future completes once it is on disk (and fails if it could not be written). */
    CompletableFuture<Void> sale(Entry entry) {
        add(entry);
        return append("S " + entry.hopper() + " " + entry.seq() + " " + entry.world() + " " + entry.x() + " " + entry.y() + " " + entry.z()
            + " " + entry.owner() + " " + String.format(Locale.ROOT, "%.2f", entry.amount()) + " " + encode(entry.stacks()));
    }

    /** The economy refused this sale and its stacks went back: nothing to take out again after a crash. */
    void voided(UUID hopper, long seq) {
        markVoided(hopper, seq);
        append("V " + hopper + " " + seq);
    }

    /** Sales up to {@code seq} of this hopper are in the saved world: forget them. */
    void saved(UUID hopper, long seq) {
        if (dropUpTo(hopper, seq) > 0) {
            append("P " + hopper + " " + seq);
        }
    }

    /** The hopper is gone from its chunk in the saved world: forget all its sales. */
    void forget(UUID hopper) {
        List<Entry> gone = live.remove(hopper);
        if (gone != null) {
            unindex(hopper, gone);
            if (!gone.isEmpty()) {
                append("P " + hopper + " " + Long.MAX_VALUE);
                deadLines.addAndGet(gone.size());
            }
        }
    }

    private void unindex(UUID hopper, List<Entry> entries) {
        ChunkId chunk;
        synchronized (entries) {
            chunk = entries.isEmpty() ? null : entries.get(0).chunk();
        }
        if (chunk != null) {
            byChunk.computeIfPresent(chunk, (key, set) -> {
                set.remove(hopper);
                return set.isEmpty() ? null : set;
            });
        } else {
            byChunk.values().forEach(set -> set.remove(hopper));
            byChunk.values().removeIf(Set::isEmpty);
        }
    }

    List<Entry> entries(UUID hopper) {
        List<Entry> entries = live.get(hopper);
        if (entries == null) {
            return List.of();
        }
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    /** The hoppers with recorded sales in this chunk. */
    List<UUID> hoppersIn(ChunkId chunk) {
        Set<UUID> hoppers = byChunk.get(chunk);
        return hoppers == null ? List.of() : List.copyOf(hoppers);
    }

    int size() {
        int size = 0;
        for (List<Entry> entries : live.values()) {
            synchronized (entries) {
                size += entries.size();
            }
        }
        return size;
    }

    boolean broken() {
        return broken;
    }

    private void add(Entry entry) {
        List<Entry> entries = live.computeIfAbsent(entry.hopper(), id -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (entries) {
            // A sale can be in the file twice (a rewrite ran while its own line was still queued): it counts once.
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).seq() == entry.seq()) {
                    if (entry.voided() && !entries.get(i).voided()) {
                        entries.set(i, entry);
                    }
                    return;
                }
            }
            entries.add(entry);
            byChunk.computeIfAbsent(entry.chunk(), key -> ConcurrentHashMap.newKeySet()).add(entry.hopper());
            if (entries.size() > MAX_PER_HOPPER) {
                entries.remove(0);
                deadLines.incrementAndGet();
                if (!warnedCap) {
                    warnedCap = true;
                    logger.info("A Chunk Hopper made more than " + MAX_PER_HOPPER + " sales without its chunk being saved; the oldest are taken as saved.");
                }
            }
        }
    }

    private void markVoided(UUID hopper, long seq) {
        List<Entry> entries = live.get(hopper);
        if (entries == null) {
            return;
        }
        synchronized (entries) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).seq() == seq) {
                    entries.set(i, entries.get(i).voidedCopy());
                }
            }
        }
    }

    private int dropUpTo(UUID hopper, long seq) {
        List<Entry> entries = live.get(hopper);
        if (entries == null) {
            return 0;
        }
        int dropped;
        ChunkId chunk;
        boolean empty;
        synchronized (entries) {
            chunk = entries.isEmpty() ? null : entries.get(0).chunk();
            int before = entries.size();
            entries.removeIf(entry -> entry.seq() <= seq);
            dropped = before - entries.size();
            empty = entries.isEmpty();
            if (empty) {
                live.remove(hopper, entries);
            }
        }
        if (empty && chunk != null) {
            byChunk.computeIfPresent(chunk, (key, set) -> {
                set.remove(hopper);
                return set.isEmpty() ? null : set;
            });
        }
        deadLines.addAndGet(dropped);
        return dropped;
    }

    // ------------------------------------------------------------------ the writer thread

    private CompletableFuture<Void> append(String line) {
        CompletableFuture<Void> durable = new CompletableFuture<>();
        if (broken) {
            durable.completeExceptionally(new IOException(file.getName() + " cannot be written"));
            return durable;
        }
        queue.add(new Pending(line, durable));
        if (scheduled.compareAndSet(false, true)) {
            try {
                writer.execute(this::drain);
            } catch (RejectedExecutionException closed) {
                scheduled.set(false);
                drain();
            }
        }
        return durable;
    }

    /** Writes everything queued as one batch, forces it to disk, then completes the batch's futures. */
    private synchronized void drain() {
        scheduled.set(false);
        List<Pending> batch = new ArrayList<>();
        for (Pending pending = queue.poll(); pending != null; pending = queue.poll()) {
            batch.add(pending);
        }
        if (batch.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Pending pending : batch) {
            text.append(pending.line()).append('\n');
        }
        try {
            if (channel == null) {
                throw new IOException(file.getName() + " is not open");
            }
            ByteBuffer bytes = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(false);
            for (Pending pending : batch) {
                pending.durable().complete(null);
            }
            if (deadLines.get() > 5_000 && deadLines.get() > 2 * size()) {
                rewrite();
                channel.close();
                channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            }
        } catch (IOException | RuntimeException failed) {
            broken = true;
            logger.log(Level.SEVERE, file.getName() + " could not be written (" + failed.getMessage() + "); Chunk Hoppers stop selling until the plugin is reloaded.", failed);
            for (Pending pending : batch) {
                pending.durable().completeExceptionally(failed);
            }
        }
    }

    /** Rewrites the file with only the sales not yet known to be saved, through a temporary file forced to disk. */
    private void rewrite() throws IOException {
        StringBuilder text = new StringBuilder();
        for (List<Entry> entries : live.values()) {
            synchronized (entries) {
                for (Entry entry : entries) {
                    text.append("S ").append(entry.hopper()).append(' ').append(entry.seq()).append(' ').append(entry.world()).append(' ')
                        .append(entry.x()).append(' ').append(entry.y()).append(' ').append(entry.z()).append(' ').append(entry.owner()).append(' ')
                        .append(String.format(Locale.ROOT, "%.2f", entry.amount())).append(' ').append(encode(entry.stacks())).append('\n');
                    if (entry.voided()) {
                        text.append("V ").append(entry.hopper()).append(' ').append(entry.seq()).append('\n');
                    }
                }
            }
        }
        Files.createDirectories(file.toPath().toAbsolutePath().getParent());
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileChannel out = FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer bytes = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                out.write(bytes);
            }
            out.force(true);
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notHere) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        deadLines.set(0);
    }

    /** Waits until everything recorded so far is on disk (the plugin is stopping, or a test needs to know). */
    void flush() {
        CompletableFuture<Void> marker = new CompletableFuture<>();
        try {
            writer.execute(() -> {
                drain();
                marker.complete(null);
            });
            marker.get(10, TimeUnit.SECONDS);
        } catch (RejectedExecutionException closed) {
            drain();
        } catch (Exception interrupted) {
            if (interrupted instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void close() {
        flush();
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            try {
                if (channel != null) {
                    channel.force(false);
                    channel.close();
                }
            } catch (IOException ignored) {
                // everything written was forced after its batch
            }
            channel = null;
        }
    }

    // ------------------------------------------------------------------ stacks as text

    /**
     * Two fields: {@code CACTUS*10;BONE*5} (material and amount, always readable) and the stacks as Bukkit serializes
     * them (item data included), base64. A stack the second cannot bring back is read from the first, without its data.
     */
    static String encode(List<ItemStack> stacks) {
        StringBuilder plain = new StringBuilder();
        for (ItemStack stack : stacks) {
            if (!plain.isEmpty()) {
                plain.append(';');
            }
            plain.append(stack.getType().name()).append('*').append(stack.getAmount());
        }
        String full;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("stacks", stacks);
            full = Base64.getEncoder().encodeToString(yaml.saveToString().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException unserializable) {
            full = "-";
        }
        return (plain.isEmpty() ? "-" : plain.toString()) + " " + full;
    }

    static List<ItemStack> decode(String plain, String full) {
        List<ItemStack> simple = new ArrayList<>();
        if (!plain.equals("-")) {
            for (String part : plain.split(";")) {
                int star = part.lastIndexOf('*');
                org.bukkit.Material material = org.bukkit.Material.matchMaterial(part.substring(0, star));
                if (material != null) {
                    simple.add(new ItemStack(material, Integer.parseInt(part.substring(star + 1))));
                }
            }
        }
        if (full.equals("-")) {
            return simple;
        }
        List<ItemStack> stacks = new ArrayList<>();
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(Base64.getDecoder().decode(full), StandardCharsets.UTF_8));
            List<?> list = yaml.getList("stacks");
            if (list != null) {
                for (Object item : list) {
                    if (item instanceof ItemStack stack) {
                        stacks.add(stack);
                    }
                }
            }
        } catch (InvalidConfigurationException | RuntimeException unreadable) {
            return simple;
        }
        return stacks.size() == simple.size() ? stacks : simple;
    }
}
