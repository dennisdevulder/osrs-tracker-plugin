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

import lombok.Data;
import java.util.HashSet;
import java.util.Set;

/**
 * Represents a shared item that should be tracked across clan members' banks.
 * These are high-value items that belong to the clan's shared storage.
 */
@Data
public class SharedItem
{
    /**
     * The OSRS item ID for this shared item (primary ID).
     */
    private int itemId;

    /**
     * The display name of the item.
     */
    private String itemName;

    /**
     * All variant IDs for this item (degraded versions, charged/uncharged, etc.).
     * For example, Guthan's helm has IDs for 100%, 75%, 50%, 25%, 0% degradation.
     * This includes the primary itemId.
     */
    private Set<Integer> variantIds;

    /**
     * The number of this item that should be in shared storage.
     * For example, 2 for "2x Twisted Bow".
     */
    private int requiredQuantity;

    /**
     * The name of the group this shared item belongs to.
     */
    private String groupName;

    /**
     * The ID of the group this shared item belongs to.
     */
    private int groupId;

    /**
     * The player who last had this item.
     */
    private String lastSeenBy;

    /**
     * When this item was last seen (ISO 8601 format).
     */
    private String lastSeenAt;

    public SharedItem()
    {
        this.requiredQuantity = 1;
        this.variantIds = new HashSet<>();
    }

    public SharedItem(int itemId, String itemName, int requiredQuantity)
    {
        this.itemId = itemId;
        this.itemName = itemName;
        this.requiredQuantity = requiredQuantity;
        this.variantIds = new HashSet<>();
        this.variantIds.add(itemId);
    }

    /**
     * Check if a given item ID matches this shared item (including variants).
     * This is used to detect degraded Barrows armor, charged weapons, etc.
     */
    public boolean matchesItemId(int checkItemId)
    {
        if (variantIds != null && !variantIds.isEmpty())
        {
            return variantIds.contains(checkItemId);
        }
        return itemId == checkItemId;
    }

    /**
     * Add a variant ID to this shared item.
     */
    public void addVariantId(int variantId)
    {
        if (variantIds == null)
        {
            variantIds = new HashSet<>();
        }
        variantIds.add(variantId);
    }
}
