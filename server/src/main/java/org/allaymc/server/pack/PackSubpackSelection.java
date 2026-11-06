package org.allaymc.server.pack;

import org.allaymc.api.pack.PackManifest;

/**
 * Resolves a configured Bedrock subpack folder to a declared manifest subpack.
 * Unconfigured or invalid names preserve the normal default ("") selection.
 */
public final class PackSubpackSelection {

    private PackSubpackSelection() {
    }

    public static String resolve(PackManifest manifest, String requestedFolder) {
        if (requestedFolder == null || requestedFolder.isBlank()
                || manifest == null || manifest.getSubpacks() == null) {
            return "";
        }

        for (var subPack : manifest.getSubpacks()) {
            if (subPack != null && requestedFolder.equals(subPack.getFolderName())) {
                return requestedFolder;
            }
        }
        return "";
    }
}
