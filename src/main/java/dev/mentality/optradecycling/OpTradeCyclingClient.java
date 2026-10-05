package dev.mentality.optradecycling;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.village.VillagerProfession;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class OpTradeCyclingClient implements ClientModInitializer {
    private static KeyBinding cycleKey;
    private static UUID lastVillagerUuid;

    /**
     * Once a villager is observed as traded, keep it locked for the rest of this
     * connection. This prevents a later transient/empty MerchantScreenHandler from
     * accidentally making the same villager look untraded.
     *
     * The lock is ignored only when the user explicitly enables the dangerous
     * bypass in config.
     */
    private static final Set<UUID> lockedVillagers = new HashSet<>();

    private static UUID pendingVillagerUuid;
    private static int reopenTicks = -1;

    @Override
    public void onInitializeClient() {
        // Creates config/optradecycling.json on first launch.
        OpTradeCyclingConfig.load();

        cycleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.optradecycling.cycle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_C,
                "category.optradecycling"
        ));

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient && entity instanceof VillagerEntity villager) {
                lastVillagerUuid = villager.getUuid();
            }
            return ActionResult.PASS;
        });

        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof MerchantScreen)) {
                return;
            }

            ScreenKeyboardEvents.beforeKeyPress(screen).register((currentScreen, key, scancode, modifiers) -> {
                if (cycleKey.matchesKey(key, scancode)) {
                    tryCycle(client);
                }
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(OpTradeCyclingClient::tickPendingReopen);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetSessionState());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetSessionState());
    }

    private static void tryCycle(MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return;
        }

        if (!(client.player.currentScreenHandler instanceof MerchantScreenHandler handler)) {
            message(client, "§cОткрой торговлю с жителем.");
            return;
        }

        if (reopenTicks >= 0) {
            message(client, "§eРеролл уже выполняется…");
            return;
        }

        ClientPlayNetworkHandler network = client.getNetworkHandler();
        if (network == null
                || network.getCommandDispatcher().getRoot().getChild("data") == null
                || network.getCommandDispatcher().getRoot().getChild("execute") == null) {
            message(client, "§cНет доступа к /data или /execute. Нужны OP-права (обычно permission level 2+).");
            return;
        }

        VillagerEntity villager = findTargetVillager(client);
        if (villager == null) {
            message(client, "§cНе удалось точно определить жителя. Закрой торговлю и открой её снова.");
            return;
        }

        UUID uuid = villager.getUuid();

        // Config is intentionally re-read on every key press so the bypass can be
        // toggled without restarting Minecraft.
        OpTradeCyclingConfig config = OpTradeCyclingConfig.load();
        boolean bypassUsedTradeProtection = config.dangerousBypassUsedTrades;

        if (!bypassUsedTradeProtection && lockedVillagers.contains(uuid)) {
            message(client, "§cУ этого жителя уже использовали сделки.");
            return;
        }

        // Even bypass mode must know the currently displayed offers before touching
        // the entity. An empty list can be a transient sync state.
        if (handler.getRecipes().isEmpty()) {
            message(client, "§eСделки ещё не загрузились. Попробуй ещё раз через секунду.");
            return;
        }

        boolean hasUsedOffer = handler.getRecipes().stream().anyMatch(offer -> offer.getUses() > 0);
        boolean hasTradeExperience = handler.getExperience() > 0;
        boolean knownAsTraded = lockedVillagers.contains(uuid) || hasUsedOffer || hasTradeExperience;

        if (!bypassUsedTradeProtection && knownAsTraded) {
            lockedVillagers.add(uuid);
            message(client, "§cУ этого жителя уже использовали сделки.");
            return;
        }

        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (profession == VillagerProfession.NONE || profession == VillagerProfession.NITWIT) {
            message(client, "§cУ этого жителя нет торговой профессии.");
            return;
        }

        Identifier professionId = Registries.VILLAGER_PROFESSION.getId(profession);
        if (professionId == null) {
            message(client, "§cНе удалось определить ID профессии.");
            return;
        }

        String exactUuidNbt = "UUID:" + uuidAsNbtIntArray(uuid);
        String selector;

        if (bypassUsedTradeProtection) {
            // DANGEROUS MODE: exact UUID remains mandatory, but Xp:0 is deliberately
            // removed so a traded villager can be reset.
            selector = "@e[type=minecraft:villager,limit=1,nbt={" + exactUuidNbt + "}]";
        } else {
            // Safe mode: final server-side guard refuses any villager with trade XP.
            selector = "@e[type=minecraft:villager,limit=1,nbt={"
                    + exactUuidNbt
                    + ",Xp:0}]";
        }

        network.sendPacket(new CloseHandledScreenC2SPacket(handler.syncId));

        if (bypassUsedTradeProtection) {
            // Full trade reset: clear offers and return the villager's trading
            // progression to novice. Gossip/reputation is intentionally preserved.
            network.sendChatCommand(
                    "execute as " + selector
                            + " run data merge entity @s {VillagerData:{profession:\"minecraft:none\",level:1},Xp:0,Offers:0b}"
            );
            network.sendChatCommand(
                    "execute as " + selector
                            + " run data merge entity @s {VillagerData:{profession:\"" + professionId + "\",level:1},Xp:0}"
            );

            // The villager is now deliberately reset, so an old session lock should
            // not keep blocking it if the bypass is disabled again afterwards.
            lockedVillagers.remove(uuid);
        } else {
            network.sendChatCommand(
                    "execute as " + selector
                            + " run data merge entity @s {VillagerData:{profession:\"minecraft:none\"},Offers:0b}"
            );
            network.sendChatCommand(
                    "execute as " + selector
                            + " run data merge entity @s {VillagerData:{profession:\"" + professionId + "\"}}"
            );
        }

        pendingVillagerUuid = uuid;
        reopenTicks = 3;

        if (bypassUsedTradeProtection && knownAsTraded) {
            message(client, "§6BYPASS: полностью сбрасываю торговый прогресс жителя…");
        } else if (bypassUsedTradeProtection) {
            message(client, "§eBYPASS включён. Обновляю сделки…");
        } else {
            message(client, "§aОбновляю сделки…");
        }
    }

    private static void tickPendingReopen(MinecraftClient client) {
        if (reopenTicks < 0) {
            return;
        }

        if (reopenTicks-- > 0) {
            return;
        }

        UUID uuid = pendingVillagerUuid;
        pendingVillagerUuid = null;
        reopenTicks = -1;

        if (uuid == null || client.player == null || client.world == null) {
            return;
        }

        VillagerEntity villager = findVillagerByUuid(client, uuid);
        if (villager == null) {
            message(client, "§eЖитель уже вне клиентской дальности — открой его вручную.");
            return;
        }

        ClientPlayNetworkHandler network = client.getNetworkHandler();
        if (network == null) {
            return;
        }

        network.sendPacket(PlayerInteractEntityC2SPacket.interact(
                villager,
                client.player.isSneaking(),
                Hand.MAIN_HAND
        ));
    }

    private static VillagerEntity findTargetVillager(MinecraftClient client) {
        if (lastVillagerUuid == null || client.player == null) {
            return null;
        }

        VillagerEntity exact = findVillagerByUuid(client, lastVillagerUuid);
        if (exact == null || exact.squaredDistanceTo(client.player) > 64.0D) {
            return null;
        }

        return exact;
    }

    private static VillagerEntity findVillagerByUuid(MinecraftClient client, UUID uuid) {
        if (client.player == null || client.world == null) {
            return null;
        }

        return client.world.getEntitiesByClass(
                        VillagerEntity.class,
                        client.player.getBoundingBox().expand(16.0D),
                        villager -> uuid.equals(villager.getUuid())
                ).stream()
                .findFirst()
                .orElse(null);
    }

    private static String uuidAsNbtIntArray(UUID uuid) {
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();

        int a = (int) (most >> 32);
        int b = (int) most;
        int c = (int) (least >> 32);
        int d = (int) least;

        return "[I;" + a + "," + b + "," + c + "," + d + "]";
    }

    private static void resetSessionState() {
        lastVillagerUuid = null;
        lockedVillagers.clear();
        pendingVillagerUuid = null;
        reopenTicks = -1;
    }

    private static void message(MinecraftClient client, String text) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(text), true);
        }
    }
}
