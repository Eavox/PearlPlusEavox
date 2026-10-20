package dev.zenith.pearlplus;

import java.util.Objects;
import java.util.UUID;

public final class AutoPearlAccess {
    private AutoPearlAccess() {}

    public static boolean mayLoad(PearlPlusConfig.AutoPearl pearl, UUID requester, String scope, boolean whitelisted) {
        return pearl == null || (whitelisted && Objects.equals(pearl.owner(), requester)
            && Objects.equals(pearl.scope(), scope));
    }
}
