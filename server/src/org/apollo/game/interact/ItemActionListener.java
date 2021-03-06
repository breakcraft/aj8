package org.apollo.game.interact;

import org.apollo.game.model.InterfaceConstants.InterfaceOption;
import org.apollo.game.model.Player;

public abstract class ItemActionListener {

    private final int interfaceId;

    public ItemActionListener(int interfaceId) {
	this.interfaceId = interfaceId;
    }

    public abstract void handle(int id, int slot, InterfaceOption option, int interfaceId, Player player);

    public final int getInterfaceId() {
	return interfaceId;
    }

}
