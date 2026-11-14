package ru.bloodorb;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * Авто-использование "Шара Крови" (Minecraft 26.2, Fabric, имена Mojang).
 * Если в радиусе RADIUS есть игрок с HP (+абсорбция) <= KILL_HP, мод берёт шар в левую руку,
 * кратко нажимает Shift и возвращает всё обратно.
 */
public class BloodOrbMod implements ClientModInitializer {

    // ===== НАСТРОЙКИ =====
    static final String ORB_NAME = "шар крови";    // часть названия предмета (регистр не важен)
    static final double RADIUS = 15.0;             // радиус действия шара (по описанию предмета)
    static final float KILL_HP = 2.0f;             // урон шара = 2
    static final boolean ONLY_LAST_TARGET = false; // true = только тот, кого ты бил последним
    static final int COOLDOWN_TICKS = 90 * 20 + 10; // КД шара 90 сек (20 тиков = 1 сек) + небольшой запас
    static final int STEP_DELAY = 2;               // задержка между шагами (тики)

    enum State { IDLE, SWAP_IN, SNEAK, RELEASE, SWAP_OUT, RESTORE }

    private static KeyMapping toggleKey;
    private static boolean enabled = false;

    private static State state = State.IDLE;
    private static int wait = 0;
    private static int cooldown = 0;
    private static int prevSlot = -1;
    private static boolean swapped = false;
    private static boolean sneakWasDown = false;
    private static Player lastTarget = null;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("bloodorb", "main"));

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bloodorb.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_R, category));

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide() && entity instanceof Player p) lastTarget = p;
            return InteractionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            reset(mc);
            return;
        }

        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            mc.player.displayClientMessage(
                    Component.literal("§c[BloodOrb] " + (enabled ? "§aВКЛ" : "§7ВЫКЛ")), true);
            if (!enabled) reset(mc);
        }
        if (!enabled) return;

        if (cooldown > 0) cooldown--;
        if (wait > 0) { wait--; return; }

        switch (state) {
            case IDLE -> {
                if (cooldown > 0) return;
                if (mc.screen != null) return;
                if (!shouldFire(mc)) return;

                if (isOrb(mc.player.getOffhandItem())) {
                    swapped = false;
                    state = State.SNEAK;
                    return;
                }
                int slot = findOrbSlot(mc);
                if (slot < 0) return;
                prevSlot = mc.player.getInventory().getSelectedSlot();
                mc.player.getInventory().setSelectedSlot(slot);
                swapped = true;
                state = State.SWAP_IN;
                wait = STEP_DELAY;
            }
            case SWAP_IN -> {
                sendSwap(mc);
                state = State.SNEAK;
                wait = STEP_DELAY;
            }
            case SNEAK -> {
                sneakWasDown = mc.options.keyShift.isDown();
                mc.options.keyShift.setDown(true);
                state = State.RELEASE;
                wait = STEP_DELAY;
            }
            case RELEASE -> {
                mc.options.keyShift.setDown(sneakWasDown);
                if (swapped) {
                    state = State.SWAP_OUT;
                    wait = 1;
                } else {
                    state = State.RESTORE;
                }
            }
            case SWAP_OUT -> {
                sendSwap(mc);
                state = State.RESTORE;
                wait = 1;
            }
            case RESTORE -> {
                if (prevSlot >= 0) mc.player.getInventory().setSelectedSlot(prevSlot);
                prevSlot = -1;
                swapped = false;
                cooldown = COOLDOWN_TICKS;
                state = State.IDLE;
            }
        }
    }

    private boolean shouldFire(Minecraft mc) {
        if (ONLY_LAST_TARGET) {
            return lastTarget != null && valid(mc, lastTarget) && lowHp(lastTarget);
        }
        for (Player p : mc.level.players()) {
            if (valid(mc, p) && lowHp(p)) return true;
        }
        return false;
    }

    private boolean valid(Minecraft mc, Player p) {
        return p != mc.player && p.isAlive() && !p.isSpectator()
                && mc.player.distanceTo(p) <= RADIUS;
    }

    private boolean lowHp(Player p) {
        float hp = p.getHealth() + p.getAbsorptionAmount();
        return hp > 0 && hp <= KILL_HP;
    }

    private boolean isOrb(ItemStack s) {
        if (s.isEmpty()) return false;
        return s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(ORB_NAME);
    }

    private int findOrbSlot(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            if (isOrb(mc.player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private void sendSwap(Minecraft mc) {
        mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
    }

    private void reset(Minecraft mc) {
        if (state != State.IDLE && mc.player != null) {
            if (swapped && mc.getConnection() != null && isOrb(mc.player.getOffhandItem())) sendSwap(mc);
            if (prevSlot >= 0) mc.player.getInventory().setSelectedSlot(prevSlot);
            mc.options.keyShift.setDown(sneakWasDown);
        }
        state = State.IDLE;
        wait = 0;
        prevSlot = -1;
        swapped = false;
    }
}
