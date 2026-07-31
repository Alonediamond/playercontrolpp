package com.alonediamond.playercontrolpp;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Client controls are wired from {@code PlayercontrolppClient}.
 */
public class Playercontrolpp implements ModInitializer {

    /**
     * The mod id, used for the config registration key, translation key prefixes and resource
     * paths. Every other class references this constant rather than repeating the literal — a
     * mismatch would make malilib silently fail to find the config instead of raising an error.
     */
    public static final String MOD_ID = "playercontrolpp";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Common initialization (currently empty)
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
