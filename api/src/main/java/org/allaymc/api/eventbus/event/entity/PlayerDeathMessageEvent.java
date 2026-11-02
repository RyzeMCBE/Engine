package org.allaymc.api.eventbus.event.entity;

import org.allaymc.api.annotation.CallerThread;
import org.allaymc.api.annotation.ThreadType;
import org.allaymc.api.entity.damage.DamageContainer;
import org.allaymc.api.entity.interfaces.EntityPlayer;
import org.allaymc.api.utils.tuple.Pair;

import java.util.Objects;

/**
 * Fired after a player dies but before the vanilla chat message and death-screen
 * packet are sent. Plugins can override either message independently.
 * Null preserves the vanilla message; an empty chat message suppresses chat.
 * The normal showDeathMessages game rule still controls chat announcements.
 */
@CallerThread(ThreadType.DIMENSION)
public final class PlayerDeathMessageEvent extends EntityEvent {
    private final DamageContainer lastDamage;
    private final Pair<String, String[]> vanillaDeathInfo;
    private String chatMessage;
    private String deathScreenMessage;

    public PlayerDeathMessageEvent(EntityPlayer player, DamageContainer lastDamage,
                                   Pair<String, String[]> vanillaDeathInfo) {
        super(Objects.requireNonNull(player));
        this.lastDamage = lastDamage;
        this.vanillaDeathInfo = Objects.requireNonNull(vanillaDeathInfo);
    }

    public EntityPlayer getPlayer() { return (EntityPlayer) getEntity(); }
    public DamageContainer getLastDamage() { return lastDamage; }
    public Pair<String, String[]> getVanillaDeathInfo() { return vanillaDeathInfo; }

    /** Raw, already formatted text; null = vanilla, blank = suppress chat. */
    public String getChatMessage() { return chatMessage; }
    public void setChatMessage(String message) { this.chatMessage = message; }

    /** Raw, already formatted screen text; null = vanilla. */
    public String getDeathScreenMessage() { return deathScreenMessage; }
    public void setDeathScreenMessage(String message) { this.deathScreenMessage = message; }
}
