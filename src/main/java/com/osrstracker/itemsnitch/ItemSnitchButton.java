/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.osrstracker.itemsnitch;

import com.osrstracker.OsrsTrackerConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ScriptEvent;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.bank.BankSearch;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages the clickable Item Snitch button in the bank interface.
 * When clicked, it toggles filtering to show only shared items in the bank.
 *
 * Follows the same pattern as RuneLite's bank tags plugin TabInterface for widget creation.
 */
@Slf4j
@Singleton
public class ItemSnitchButton
{
    private static final int BUTTON_SIZE = 18;  // Smaller, more subtle
    private static final int BUTTON_MARGIN = 5;

    private final Client client;
    private final ClientThread clientThread;
    private final OsrsTrackerConfig config;
    private final SpriteManager spriteManager;
    private final ItemSnitchTracker tracker;
    private final BankSearch bankSearch;

    private Widget snitchButton;

    @Getter
    private boolean filterActive = false;

    @Inject
    public ItemSnitchButton(
        Client client,
        ClientThread clientThread,
        OsrsTrackerConfig config,
        SpriteManager spriteManager,
        ItemSnitchTracker tracker,
        BankSearch bankSearch)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.spriteManager = spriteManager;
        this.tracker = tracker;
        this.bankSearch = bankSearch;
    }

    /**
     * Initialize sprites on plugin startup and register for initialization callback.
     */
    public void loadSprites()
    {
        spriteManager.addSpriteOverrides(ItemSnitchSprites.values());
        log.debug("Item Snitch sprites loaded");

        // Register callback to refresh button when shared items are loaded from API
        tracker.setOnInitializedCallback(() -> {
            log.debug("Item Snitch initialized callback triggered");
            if (tracker.isBankOpen())
            {
                clientThread.invokeLater(this::createButton);
            }
        });
    }

    /**
     * Called when the bank widget is loaded (WidgetLoaded event).
     * Just resets state - actual button creation happens in onBankFinishedBuilding.
     */
    public void onBankOpen()
    {
        log.info("Bank widget loaded - resetting state");
        // Only reset filter state, don't touch snitchButton here as
        // onBankFinishedBuilding may have already created it
        filterActive = false;
    }

    /**
     * Called when the bank finishes building (via ScriptPostFired for BANKMAIN_FINISHBUILDING).
     * This is when we actually create the button.
     */
    public void onBankFinishedBuilding()
    {
        log.info("Bank finished building - creating Item Snitch button");
        // Reset button reference before creating new one
        snitchButton = null;
        createButton();
    }

    /**
     * Called when the bank interface closes.
     */
    public void onBankClose()
    {
        filterActive = false;
        snitchButton = null;
    }

    /**
     * Creates the snitch button in the bank interface.
     * Uses the same approach as RuneLite's bank tags plugin - creates a child widget
     * on the ITEMS_CONTAINER and positions it relative to other bank elements.
     */
    private void createButton()
    {
        // Check if we already have a button
        if (snitchButton != null)
        {
            log.debug("Button already exists, skipping creation");
            return;
        }

        log.info("Creating Item Snitch button...");

        // Try using the bank FRAME widget as the parent (the main bank window)
        Widget parent = client.getWidget(InterfaceID.Bankmain.FRAME);
        if (parent == null)
        {
            log.warn("Bank FRAME not found, trying ITEMS_CONTAINER");
            parent = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
        }
        if (parent == null)
        {
            log.warn("No suitable parent widget found");
            return;
        }

        log.info("Using parent widget: {} (size: {}x{})",
            parent.getId(), parent.getWidth(), parent.getHeight());

        // Create the button widget as a child of the parent
        snitchButton = parent.createChild(-1, WidgetType.GRAPHIC);
        if (snitchButton == null)
        {
            log.warn("Failed to create snitch button widget");
            return;
        }

        // Position in the top-right area, near the close button and settings
        // Place it to the left of where the settings/menu buttons would be
        int buttonX = parent.getWidth() - BUTTON_SIZE - 100;  // 100px from right edge
        int buttonY = 8;  // Vertically centered in title bar area

        log.info("Positioning button at ({}, {}) on parent (size: {}x{})",
            buttonX, buttonY, parent.getWidth(), parent.getHeight());

        // Configure the button
        // Try using a standard game sprite first to test visibility (SpriteID.TAB_QUESTS = 776)
        // If this works, then we know the issue is with our custom sprite
        int spriteId = ItemSnitchSprites.SNITCH_ICON.getSpriteId();
        log.info("Setting sprite ID: {} (custom sprite)", spriteId);

        snitchButton.setSpriteId(spriteId);
        snitchButton.setOriginalWidth(BUTTON_SIZE);
        snitchButton.setOriginalHeight(BUTTON_SIZE);
        snitchButton.setOriginalX(buttonX);
        snitchButton.setOriginalY(buttonY);

        // Make sure the widget is not hidden
        snitchButton.setHidden(false);

        // Set up click handling
        snitchButton.setAction(1, "Filter shared items");
        snitchButton.setHasListener(true);
        snitchButton.setOnOpListener((JavaScriptCallback) this::onButtonClick);

        // Set tooltip with item count
        int itemCount = tracker.getCurrentBankSharedItems().size();
        snitchButton.setName("<col=ff6400>Item Snitch</col> (" + itemCount + " shared)");

        // Enable mouse interactions (same pattern as bank tags)
        int clickMask = snitchButton.getClickMask();
        clickMask |= WidgetConfig.USE_WIDGET;
        snitchButton.setClickMask(clickMask);

        snitchButton.revalidate();

        log.info("Created Item Snitch button at position ({}, {}) on ITEMS_CONTAINER (parent size: {}x{})",
            buttonX, buttonY, parent.getWidth(), parent.getHeight());
    }

    /**
     * Handles button click to toggle filtering.
     */
    private void onButtonClick(ScriptEvent event)
    {
        filterActive = !filterActive;
        log.info("Item Snitch filter toggled: {}", filterActive);

        // Update button appearance
        updateButtonState();

        // Trigger bank layout refresh to apply filter using BankSearch
        bankSearch.layoutBank();
    }

    /**
     * Updates the button sprite based on filter state.
     */
    private void updateButtonState()
    {
        if (snitchButton == null)
        {
            return;
        }

        if (filterActive)
        {
            snitchButton.setSpriteId(ItemSnitchSprites.SNITCH_ICON_ACTIVE.getSpriteId());
            snitchButton.setAction(1, "Show all items");
        }
        else
        {
            snitchButton.setSpriteId(ItemSnitchSprites.SNITCH_ICON.getSpriteId());
            snitchButton.setAction(1, "Filter shared items");
        }
        snitchButton.revalidate();
    }

    /**
     * Checks if the filter is currently active (used by tracker for filtering logic).
     */
    public boolean isFilterActive()
    {
        return filterActive;
    }

    /**
     * Refreshes the button state (e.g., after bank items change).
     */
    public void refresh()
    {
        if (!tracker.isBankOpen())
        {
            return;
        }

        // If we have shared items but no button, create it
        if (snitchButton == null && !tracker.getCurrentBankSharedItems().isEmpty())
        {
            createButton();
        }
        // If button exists, update the count in tooltip
        else if (snitchButton != null)
        {
            int itemCount = tracker.getCurrentBankSharedItems().size();
            if (itemCount == 0)
            {
                // Hide button if no more shared items
                snitchButton.setHidden(true);
            }
            else
            {
                snitchButton.setName("<col=aa00ff>Item Snitch</col> (" + itemCount + " shared)");
                snitchButton.setHidden(false);
            }
        }
    }
}
