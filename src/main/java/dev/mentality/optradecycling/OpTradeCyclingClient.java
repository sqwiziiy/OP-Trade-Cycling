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
import net.minecraft.registry.Registries;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
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
     */
    private static final Set<UUID> lockedVillagers = new HashSet<>();

    private static UUID pendingVillagerUuid;
    private static int reopenTicks = -1;

    @Override
    public void onInitializeClient() {
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

        // UUID safety locks are only meaningful for the current connection/world.
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

        // Safety first: never guess which villager belongs to the open merchant GUI.
        // If the normal right-click callback did not give us an exact UUID, do nothing.
        VillagerEntity villager = findTargetVillager(client);
        if (villager == null) {
            message(client, "§cНе удалось точно определить жителя. Закрой торговлю и открой её снова.");
            return;
        }

        UUID uuid = villager.getUuid();

        // Permanent lock for this connection once the villager was ever observed as traded.
        if (lockedVillagers.contains(uuid)) {
            message(client, "§cУ этого жителя уже использовали сделки.");
            return;
        }

        // An empty list can be a transient client sync state. Treat UNKNOWN as unsafe,
        // never as an untraded villager.
        if (handler.getRecipes().isEmpty()) {
            message(client, "§eСделки ещё не загрузились. Попробуй ещё раз через секунду.");
            return;
        }

        // Two independent client-side indicators:
        // 1) exact per-offer use count;
        // 2) villager trade XP sent by the server with the merchant screen.
        // For vanilla villagers, any completed trade gives the villager trade XP.
        boolean hasUsedOffer = handler.getRecipes().stream().anyMatch(offer -> offer.getUses() > 0);
        boolean hasTradeExperience = handler.getExperience() > 0;

        if (hasUsedOffer || hasTradeExperience) {
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

        // Server-side final safety net.
        // We target the exact UUID through its NBT and additionally require Xp:0.
        // If this villager has ever been traded with in normal vanilla gameplay,
        // the selector matches nothing and BOTH destructive /data commands are skipped.
        String safeSelector = "@e[type=minecraft:villager,limit=1,nbt={UUID:"
                + uuidAsNbtIntArray(uuid)
                + ",Xp:0}]";

        // Close only the SERVER handler. The visible client MerchantScreen stays open.
        network.sendPacket(new CloseHandledScreenC2SPacket(handler.syncId));

        network.sendChatCommand(
                "execute as " + safeSelector
                        + " run data merge entity @s {VillagerData:{profession:\"minecraft:none\"},Offers:0b}"
        );
        network.sendChatCommand(
                "execute as " + safeSelector
                        + " run data merge entity @s {VillagerData:{profession:\"" + professionId + "\"}}"
        );

        pendingVillagerUuid = uuid;
        reopenTicks = 3;
        message(client, "§aОбновляю сделки…");
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
