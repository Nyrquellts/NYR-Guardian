package com.nyr.guardian.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** A plugin's one command: subcommands each with their own permission, and help that lists only what the sender may run. */
public final class NyrCommand implements CommandExecutor, TabCompleter {

    @FunctionalInterface
    public interface Handler {
        void run(CommandSender sender, String[] args);
    }

    @FunctionalInterface
    public interface Completer {
        List<String> complete(CommandSender sender, String[] args);
    }

    private record Sub(String name, String usage, String permission, String description, Handler handler, Completer completer) {
    }

    private final Messages messages;
    private final Map<String, Sub> subs = new LinkedHashMap<>();
    private String byDefault;

    public NyrCommand(Messages messages) {
        this.messages = messages;
    }

    /** {@code usage} follows the name in help, e.g. "[player]"; {@code completer} may be null. */
    public NyrCommand add(String name, String usage, String permission, String description, Handler handler, Completer completer) {
        subs.put(name.toLowerCase(Locale.ROOT), new Sub(name, usage, permission, description, handler, completer));
        return this;
    }

    /** The subcommand run when the command is typed alone by someone allowed to use it; others see help. */
    public NyrCommand byDefault(String name) {
        this.byDefault = name.toLowerCase(Locale.ROOT);
        return this;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 && byDefault != null) {
            Sub sub = subs.get(byDefault);
            if (sub != null && sender.hasPermission(sub.permission())) {
                sub.handler().run(sender, args);
                return true;
            }
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender, label);
            return true;
        }
        Sub sub = subs.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) {
            messages.send(sender, "unknown-command", "label", label);
            return true;
        }
        if (!sender.hasPermission(sub.permission())) {
            messages.send(sender, "no-permission");
            return true;
        }
        sub.handler().run(sender, Arrays.copyOfRange(args, 1, args.length));
        return true;
    }

    private void help(CommandSender sender, String label) {
        messages.send(sender, "help-header", "label", label);
        boolean any = false;
        for (Sub sub : subs.values()) {
            if (sender.hasPermission(sub.permission())) {
                String usage = sub.usage().isEmpty() ? "" : " " + sub.usage();
                messages.send(sender, "help-line", "label", label, "command", sub.name() + usage, "description", sub.description());
                any = true;
            }
        }
        if (!any) {
            messages.send(sender, "no-permission");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            for (Sub sub : subs.values()) {
                if (sub.name().toLowerCase(Locale.ROOT).startsWith(typed) && sender.hasPermission(sub.permission())) {
                    out.add(sub.name());
                }
            }
            return out;
        }
        Sub sub = args.length == 0 ? null : subs.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null || sub.completer() == null || !sender.hasPermission(sub.permission())) {
            return out;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        String typed = rest.length == 0 ? "" : rest[rest.length - 1].toLowerCase(Locale.ROOT);
        for (String option : sub.completer().complete(sender, rest)) {
            if (option.toLowerCase(Locale.ROOT).startsWith(typed)) {
                out.add(option);
            }
        }
        return out;
    }
}
