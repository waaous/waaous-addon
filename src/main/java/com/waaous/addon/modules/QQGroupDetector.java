package com.waaous.addon.modules;

import com.waaous.addon.WaaousAddon;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.entity.boss.BossBar;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ 群号识别：从聊天栏（含私信）与 boss 条中提取合法 QQ 群号，去重后保存到
 * {@code .minecraft/meteor-client/QQnumber.txt}。
 */
public class QQGroupDetector extends Module {

    // 合法 QQ 群号：5~10 位数字，且不能以 0 开头、不能是更长数字串的一部分。
    private static final Pattern GROUP_PATTERN = Pattern.compile("(?<!\\d)[1-9]\\d{4,9}(?!\\d)");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> groups = sgGeneral.add(new StringListSetting.Builder()
        .name("qq-groups")
        .description("已识别的 QQ 群号（也可在下方预览）。")
        .build()
    );

    private final Setting<Boolean> autoCopy = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-copy")
        .description("检测到新群号时自动复制到剪贴板。")
        .defaultValue(true)
        .build()
    );

    private final List<String> numbers = new ArrayList<>();

    public QQGroupDetector() {
        super(WaaousAddon.CATEGORY, "qq-group-detector",
              "识别聊天栏（含私信）与 boss 条中的 QQ 群号，去重保存到 QQnumber.txt。");
    }

    @Override
    public void onActivate() {
        load();
    }

    // 聊天（含私信，私信消息同样走聊天接收事件）
    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        scan(event.getMessage().getString());
    }

    // 每 tick 扫一次 boss 条
    @EventHandler
    private void onTick(TickEvent.Post event) {
        scanBossBars();
    }

    private void scanBossBars() {
        try {
            BossBarHud hud = mc.inGameHud.getBossBarHud();
            Field field = BossBarHud.class.getDeclaredField("field_2060"); // 中间名：bars 字段
            field.setAccessible(true);
            Map<?, ?> bars = (Map<?, ?>) field.get(hud);
            for (Object o : bars.values()) {
                if (o instanceof BossBar bar) {
                    scan(bar.getName().getString());
                }
            }
        } catch (Throwable ignored) {
            // 反射失败则跳过，不影响主流程
        }
    }

    private void scan(String text) {
        if (text == null) return;
        Matcher m = GROUP_PATTERN.matcher(text);
        while (m.find()) {
            addGroup(m.group());
        }
    }

    private void addGroup(String group) {
        if (numbers.contains(group)) return; // 去重
        numbers.add(group);
        groups.get().add(group);
        save();
        if (autoCopy.get()) {
            mc.keyboard.setClipboard(group);
        }
    }

    private Path file() {
        return mc.runDirectory.toPath().resolve("meteor-client").resolve("QQnumber.txt");
    }

    private void load() {
        numbers.clear();
        groups.get().clear();
        try {
            Path p = file();
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (!line.isEmpty() && !numbers.contains(line)) {
                        numbers.add(line);
                        groups.get().add(line);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void save() {
        try {
            Path p = file();
            Files.createDirectories(p.getParent()); // 没有彗星文件夹则创建
            Files.write(p, numbers, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }
}
