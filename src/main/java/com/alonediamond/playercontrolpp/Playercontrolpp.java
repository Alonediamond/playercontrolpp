package com.alonediamond.playercontrolpp;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. PlayerControl++ is client-only, so everything it actually does is wired up
 * from {@code PlayercontrolppClient} and {@code InitHandler}; this class exists to hold the mod id
 * and the logger, which are the single source of truth for both.
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
        // Nothing to do on the common side.
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
