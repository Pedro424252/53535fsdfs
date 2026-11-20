package com.example.money;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.text.Text;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Money - Gaks_v310
 *
 * "xxx isimli oyuncu size ışınlanmak istiyor [onayla] [reddet]" mesaji gelirse ve
 * xxx izinli listedeyse (varsayilan: Gaks_v310), delaySec saniye sonra "/tpaccept xxx" yazar.
 */
public class Money implements ClientModInitializer {

    /** 1. grup = isteyen oyuncu adi */
    private static final Pattern REQUEST = Pattern.compile(
            "(?i)([A-Za-z0-9_.]{1,20})\\s+isimli\\s+oyuncu\\s+size\\s+\\S+\\s+istiyor");
    /** "xxx isimli oyuncunun ışınlanma isteğini kabul ettiniz" -> 1. grup = oyuncu adi */
    private static final Pattern ACCEPTED = Pattern.compile(
            "(?i)([A-Za-z0-9_.]{1,20})\\s+isimli\\s+oyuncunun\\s+\\S+\\s+\\S+\\s+kabul\\s+ettiniz");
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_.]{1,20}");

    public static volatile boolean enabled = true;
    public static volatile int delaySec = 15;
    /** izinli oyuncularin istek ve kabul mesajlarini sohbetten gizle */
    public static volatile boolean hideMessages = true;
    /** kucuk harfli izinli isimler */
    private static final Set<String> ALLOWED = ConcurrentHashMap.newKeySet();
    /** kucuk harfli isim -> {gercek isim, gonderilecek zaman (ms)} */
    private static final Map<String, Object[]> PENDING = new ConcurrentHashMap<>();

    static { ALLOWED.add("gaks_v310"); }

    // ------------------------------------------------------------ ayar dosyasi

    private static Path cfgPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("money.properties");
    }

    private static void save() {
        try (OutputStream out = Files.newOutputStream(cfgPath())) {
            Properties p = new Properties();
            p.setProperty("enabled", String.valueOf(enabled));
            p.setProperty("delaySec", String.valueOf(delaySec));
            p.setProperty("hideMessages", String.valueOf(hideMessages));
            p.setProperty("allowed", String.join(",", ALLOWED));
            p.store(out, "Money");
        } catch (Throwable ignored) { }
    }

    private static void load() {
        Path path = cfgPath();
        if (!Files.exists(path)) { save(); return; }
        try (InputStream in = Files.newInputStream(path)) {
            Properties p = new Properties();
            p.load(in);
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
            try { delaySec = Math.max(0, Integer.parseInt(p.getProperty("delaySec", "15").trim())); }
            catch (Throwable t) { delaySec = 15; }
            hideMessages = Boolean.parseBoolean(p.getProperty("hideMessages", "true"));
            ALLOWED.clear();
            for (String s : p.getProperty("allowed", "gaks_v310").split(",")) {
                s = s.trim().toLowerCase(Locale.ROOT);
                if (!s.isEmpty()) ALLOWED.add(s);
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ mesaj yakalama

    private static String clean(String in) {
        return in.replaceAll("[\\u200B-\\u200D\\uFEFF\\u00AD]", "")
                .replaceAll("[\\u00A0\\u2007\\u202F\\u2009\\u200A]", " ");
    }

    /** false dondurursek mesaj sohbette gorunmez. */
    private static boolean handleMessage(Text message) {
        if (!enabled) return true;
        try {
            String text = clean(message.getString());

            Matcher m = REQUEST.matcher(text);
            if (m.find()) {
                String name = m.group(1);
                String key = name.toLowerCase(Locale.ROOT);
                if (!ALLOWED.contains(key) || !VALID_NAME.matcher(name).matches()) return true;
                // ayni istek (chat + game kanalindan) iki kez gelirse ilkini koru
                PENDING.putIfAbsent(key, new Object[]{name, System.currentTimeMillis() + delaySec * 1000L});
                return !hideMessages;
            }

            Matcher a = ACCEPTED.matcher(text);
            if (a.find() && ALLOWED.contains(a.group(1).toLowerCase(Locale.ROOT))) {
                return !hideMessages;
            }
        } catch (Throwable ignored) { }
        return true;
    }

    private static void tick(MinecraftClient mc) {
        if (PENDING.isEmpty()) return;
        ClientPlayNetworkHandler nh = mc.getNetworkHandler();
        if (nh == null) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Object[]>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object[]> e = it.next();
            if (now >= (Long) e.getValue()[1]) {
                it.remove();
                try { nh.sendChatCommand("tpaccept " + e.getValue()[0]); } catch (Throwable ignored) { }
            }
        }
    }

    // ------------------------------------------------------------ giris noktasi

    @Override
    public void onInitializeClient() {
        load();

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || handleMessage(message));
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, ts) -> handleMessage(message));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null || !enabled) {
                PENDING.clear();
                return;
            }
            try { tick(client); } catch (Throwable ignored) { }
        });
    }
}
