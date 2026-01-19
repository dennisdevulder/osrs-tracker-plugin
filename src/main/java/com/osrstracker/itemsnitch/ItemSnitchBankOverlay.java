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
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;

/**
 * Overlay that highlights shared items in the bank interface.
 * Draws colored borders around shared items to make them easy to identify.
 *
 * The info panel has been removed in favor of the clickable ItemSnitchButton
 * which provides a cleaner, more integrated bank interface experience.
 */
@Slf4j
@Singleton
public class ItemSnitchBankOverlay extends Overlay
{
    private final Client client;
    private final OsrsTrackerConfig config;
    private final ItemSnitchTracker tracker;

    // Colors for highlighting - amber/gold theme matching OSRS style
    private static final Color SHARED_ITEM_BORDER = new Color(255, 176, 46, 220);      // Gold border
    private static final Color SHARED_ITEM_FILL = new Color(255, 176, 46, 40);         // Subtle gold fill
    private static final Color SHARED_ITEM_INDICATOR = new Color(255, 100, 0, 255);    // Orange indicator dot

    @Inject
    public ItemSnitchBankOverlay(Client client, OsrsTrackerConfig config, ItemSnitchTracker tracker)
    {
        this.client = client;
        this.config = config;
        this.tracker = tracker;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        // Only render if Item Snitch is enabled, highlighting is on, and bank is open
        if (!config.trackItemSnitch() || !config.itemSnitchHighlight() || !tracker.isBankOpen() || !tracker.isInitialized())
        {
            return null;
        }

        // Get shared items found in bank
        var sharedItems = tracker.getCurrentBankSharedItems();
        if (sharedItems.isEmpty())
        {
            return null;
        }

        // Get the bank item container widget
        Widget bankItemContainer = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
        if (bankItemContainer == null || bankItemContainer.isHidden())
        {
            return null;
        }

        // Get the bank container bounds to clip rendering
        Rectangle bankBounds = bankItemContainer.getBounds();
        if (bankBounds == null)
        {
            return null;
        }

        // Save the original clip and set clip to bank bounds
        Shape originalClip = graphics.getClip();
        graphics.setClip(bankBounds);

        // Highlight shared items in the bank
        highlightSharedItems(graphics, bankItemContainer);

        // Restore original clip
        graphics.setClip(originalClip);

        return null;
    }

    /**
     * Highlights shared items in the bank with a colored border and subtle fill.
     */
    private void highlightSharedItems(Graphics2D graphics, Widget bankContainer)
    {
        Widget[] children = bankContainer.getDynamicChildren();
        if (children == null)
        {
            return;
        }

        var sharedItemIds = tracker.getSharedItemIds();

        // Enable anti-aliasing for smoother drawing
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (Widget item : children)
        {
            if (item == null || item.isHidden())
            {
                continue;
            }

            int itemId = item.getItemId();
            if (itemId == -1 || !sharedItemIds.contains(itemId))
            {
                continue;
            }

            // Get item bounds
            Rectangle bounds = item.getBounds();
            if (bounds == null || bounds.width == 0 || bounds.height == 0)
            {
                continue;
            }

            // Draw subtle fill
            graphics.setColor(SHARED_ITEM_FILL);
            graphics.fillRect(bounds.x + 1, bounds.y + 1, bounds.width - 2, bounds.height - 2);

            // Draw highlight border around the item
            graphics.setColor(SHARED_ITEM_BORDER);
            graphics.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.drawRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);

            // Draw a small warning indicator in the top-right corner
            int indicatorSize = 8;
            int indicatorX = bounds.x + bounds.width - indicatorSize - 2;
            int indicatorY = bounds.y + 2;

            // Draw indicator background (dark)
            graphics.setColor(new Color(0, 0, 0, 180));
            graphics.fillOval(indicatorX - 1, indicatorY - 1, indicatorSize + 2, indicatorSize + 2);

            // Draw indicator (orange warning dot)
            graphics.setColor(SHARED_ITEM_INDICATOR);
            graphics.fillOval(indicatorX, indicatorY, indicatorSize, indicatorSize);

            // Draw exclamation mark in the indicator
            graphics.setColor(Color.WHITE);
            graphics.setFont(graphics.getFont().deriveFont(Font.BOLD, 7f));
            FontMetrics fm = graphics.getFontMetrics();
            String mark = "!";
            int textX = indicatorX + (indicatorSize - fm.stringWidth(mark)) / 2;
            int textY = indicatorY + (indicatorSize + fm.getAscent() - fm.getDescent()) / 2 - 1;
            graphics.drawString(mark, textX, textY);
        }
    }
}
