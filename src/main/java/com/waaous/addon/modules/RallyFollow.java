package com.waaous.addon.modules;

import com.mojang.authlib.GameProfile;
import com.waaous.addon.WaaousAddon;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.mixininterface.IMessageHandler;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

import java.util.UUID;

/**
 * 挂电集结跟随：公开聊天（非私聊）中出现关键词时，自动在聊天栏回复自定义消息。
 *
 * <p>触发规则：
 * <ol>
 *   <li>消息必须是<b>公开聊天</b>（签名聊天，带发送者），私聊（{@code /msg}、{@code /tell}）
 *       走系统消息通道、无签名发送者，不会触发；</li>
 *   <li>消息正文包含关键词（默认「挂电集结」）；</li>
 *   <li>自己发出的消息被忽略，避免自触发。</li>
 * </ol>
 * 回复内容与关键词均可在 Meteor 的模块配置界面修改。
 */
public class RallyFollow extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> keyword = sgGeneral.add(new StringSetting.Builder()
        .name("keyword")
        .description("触发关键词：公开聊天中包含该关键词时自动回复（私聊信息不会触发）。")
        .defaultValue("挂电集结")
        .build()
    );

    private final Setting<String> reply = sgGeneral.add(new StringSetting.Builder()
        .name("reply")
        .description("触发后自动发送到公开聊天的消息。")
        .defaultValue("谁可思谁闻")
        .build()
    );

    private final Setting<Integer> cooldown = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown")
        .description("两次自动回复之间的最小间隔（tick，20 = 1 秒），避免刷屏。")
        .defaultValue(20).min(0).max(200).sliderRange(0, 100).build()
    );

    /** 上次自动回复时的玩家 tick 计数；初始化为一个足够小的负数，保证首次必然触发。 */
    private int lastSendTick = Integer.MIN_VALUE / 2;

    public RallyFollow() {
        super(WaaousAddon.CATEGORY, "rally-follow",
              "公开聊天出现关键词时自动回复自定义消息（挂电集结跟随）。");
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (mc.player == null) return;

        String text = event.getMessage().getString();
        if (text == null || text.isEmpty()) return;

        String kw = keyword.get().trim();
        if (kw.isEmpty() || !text.contains(kw)) return;

        // 只响应公开聊天，私聊 / 系统消息（无签名发送者）除外，并忽略自己发的消息。
        if (!isPublicChat()) return;

        // 冷却，防止多人同时喊话时连续刷屏。
        if (mc.player.age - lastSendTick < cooldown.get()) return;

        String msg = reply.get();
        if (msg == null || msg.isEmpty()) return;

        lastSendTick = mc.player.age;
        ChatUtils.sendPlayerMsg(msg);
    }

    /**
     * 公开聊天消息带签名发送者；私聊（{@code /msg}、{@code /tell}）走系统消息通道，发送者为 null。
     * 返回 false 表示私聊 / 系统消息 / 自己发送的消息，均不触发。
     */
    private boolean isPublicChat() {
        try {
            GameProfile sender = ((IMessageHandler) mc.getMessageHandler()).meteor$getSender();
            if (sender == null) return false;

            // 忽略自己发的消息，避免把回复当成触发源造成自触发。
            UUID self = mc.player.getGameProfile().id();
            UUID senderId = sender.id();
            return self == null || senderId == null || !senderId.equals(self);
        } catch (Throwable t) {
            return false; // 反射 / 类型转换失败时保守处理，不触发
        }
    }
}
