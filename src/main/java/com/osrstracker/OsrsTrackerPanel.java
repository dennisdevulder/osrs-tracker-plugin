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
package com.osrstracker;

import com.osrstracker.bingo.BingoSubscriptionManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;

/**
 * Simple sidebar panel for OSRS Tracker with a Quick Capture button
 * and active bingo event display.
 */
public class OsrsTrackerPanel extends PluginPanel
{
    private final Runnable quickCaptureAction;
    private final BingoSubscriptionManager bingoManager;
    private JButton captureButton;
    private JLabel statusLabel;

    // Bingo section components
    private JPanel bingoPanel;
    private JLabel bingoEventLabel;
    private JLabel bingoStatusLabel;
    private JLabel bingoProgressLabel;

    // Colors for different states
    private static final Color COLOR_READY = new Color(46, 204, 113);      // Green
    private static final Color COLOR_RECORDING = new Color(241, 196, 15);  // Yellow/Orange
    private static final Color COLOR_ENCODING = new Color(52, 152, 219);   // Blue
    private static final Color COLOR_UPLOADING = new Color(155, 89, 182);  // Purple
    private static final Color COLOR_ERROR = new Color(231, 76, 60);       // Red
    private static final Color COLOR_COOLDOWN = new Color(149, 165, 166);  // Gray
    private static final Color COLOR_BINGO_ACTIVE = new Color(46, 204, 113);   // Green
    private static final Color COLOR_BINGO_INACTIVE = new Color(149, 165, 166); // Gray

    public OsrsTrackerPanel(Runnable quickCaptureAction, BingoSubscriptionManager bingoManager)
    {
        super(false);
        this.quickCaptureAction = quickCaptureAction;
        this.bingoManager = bingoManager;

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Create main content panel
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        contentPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Title
        JLabel titleLabel = new JLabel("OSRS Tracker");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 16));
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        contentPanel.add(titleLabel);

        contentPanel.add(Box.createVerticalStrut(20));

        // Quick Capture Button
        captureButton = new JButton("Quick Capture");
        captureButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        captureButton.setMaximumSize(new Dimension(200, 40));
        captureButton.setBackground(new Color(93, 173, 226));
        captureButton.setForeground(Color.WHITE);
        captureButton.setFocusPainted(false);
        captureButton.setFont(new Font("Arial", Font.BOLD, 14));
        captureButton.addActionListener(e -> {
            if (quickCaptureAction != null && captureButton.isEnabled())
            {
                quickCaptureAction.run();
            }
        });
        contentPanel.add(captureButton);

        contentPanel.add(Box.createVerticalStrut(10));

        // Description
        JLabel descLabel = new JLabel("<html><center>Captures the last 8 seconds<br>+ 2 seconds after click</center></html>");
        descLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        descLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        contentPanel.add(descLabel);

        contentPanel.add(Box.createVerticalStrut(20));

        // Status label
        statusLabel = new JLabel("Ready to capture");
        statusLabel.setForeground(COLOR_READY);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        contentPanel.add(statusLabel);

        contentPanel.add(Box.createVerticalStrut(30));

        // Bingo section
        bingoPanel = createBingoSection();
        contentPanel.add(bingoPanel);

        add(contentPanel, BorderLayout.NORTH);

        // Register for bingo subscription changes
        if (bingoManager != null)
        {
            bingoManager.setOnSubscriptionChanged(this::updateBingoSection);
        }
    }

    /**
     * Creates the bingo event section of the panel.
     */
    private JPanel createBingoSection()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1, true),
            new EmptyBorder(10, 10, 10, 10)
        ));
        panel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));

        // Section title
        JLabel titleLabel = new JLabel("Active Bingo Event");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 12));
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(titleLabel);

        panel.add(Box.createVerticalStrut(8));

        // Event name
        bingoEventLabel = new JLabel("None");
        bingoEventLabel.setFont(new Font("Arial", Font.PLAIN, 11));
        bingoEventLabel.setForeground(COLOR_BINGO_INACTIVE);
        bingoEventLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(bingoEventLabel);

        panel.add(Box.createVerticalStrut(4));

        // Status indicator
        bingoStatusLabel = new JLabel("\u25CF Not Tracking");  // ● bullet point
        bingoStatusLabel.setFont(new Font("Arial", Font.BOLD, 11));
        bingoStatusLabel.setForeground(COLOR_BINGO_INACTIVE);
        bingoStatusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(bingoStatusLabel);

        panel.add(Box.createVerticalStrut(4));

        // Progress info
        bingoProgressLabel = new JLabel("");
        bingoProgressLabel.setFont(new Font("Arial", Font.PLAIN, 10));
        bingoProgressLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        bingoProgressLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(bingoProgressLabel);

        return panel;
    }

    /**
     * Updates the bingo section based on current subscription state.
     */
    public void updateBingoSection()
    {
        SwingUtilities.invokeLater(() -> {
            if (bingoManager == null)
            {
                return;
            }

            if (bingoManager.hasActiveEvent())
            {
                String eventName = bingoManager.getActiveEventName();
                if (eventName != null && eventName.length() > 25)
                {
                    eventName = eventName.substring(0, 22) + "...";
                }

                bingoEventLabel.setText(eventName != null ? eventName : "Active Event");
                bingoEventLabel.setForeground(Color.WHITE);

                bingoStatusLabel.setText("\u25CF Tracking");
                bingoStatusLabel.setForeground(COLOR_BINGO_ACTIVE);

                int tracked = bingoManager.getTrackedTileCount();
                int completed = bingoManager.getCompletedTileCount();
                if (tracked > 0)
                {
                    bingoProgressLabel.setText(completed + " / " + tracked + " tiles tracked");
                }
                else
                {
                    bingoProgressLabel.setText("Waiting for tiles...");
                }
            }
            else
            {
                bingoEventLabel.setText("None");
                bingoEventLabel.setForeground(COLOR_BINGO_INACTIVE);

                bingoStatusLabel.setText("\u25CF Not Tracking");
                bingoStatusLabel.setForeground(COLOR_BINGO_INACTIVE);

                bingoProgressLabel.setText("");
            }
        });
    }

    /**
     * Update the panel to show "recording" state.
     */
    public void setRecordingState()
    {
        SwingUtilities.invokeLater(() -> {
            captureButton.setEnabled(false);
            captureButton.setText("Recording...");
            captureButton.setBackground(COLOR_RECORDING);
            statusLabel.setText("Recording 2 more seconds...");
            statusLabel.setForeground(COLOR_RECORDING);
        });
    }

    /**
     * Update the panel to show "encoding" state.
     */
    public void setEncodingState()
    {
        SwingUtilities.invokeLater(() -> {
            captureButton.setEnabled(false);
            captureButton.setText("Encoding...");
            captureButton.setBackground(COLOR_ENCODING);
            statusLabel.setText("Encoding video...");
            statusLabel.setForeground(COLOR_ENCODING);
        });
    }

    /**
     * Update the panel to show "uploading" state.
     */
    public void setUploadingState()
    {
        SwingUtilities.invokeLater(() -> {
            captureButton.setEnabled(false);
            captureButton.setText("Uploading...");
            captureButton.setBackground(COLOR_UPLOADING);
            statusLabel.setText("Uploading to server...");
            statusLabel.setForeground(COLOR_UPLOADING);
        });
    }

    /**
     * Update the panel to show "cooldown" state with remaining time.
     */
    public void setCooldownState(int secondsRemaining)
    {
        SwingUtilities.invokeLater(() -> {
            captureButton.setEnabled(false);
            captureButton.setText("Cooldown " + secondsRemaining + "s");
            captureButton.setBackground(COLOR_COOLDOWN);
            statusLabel.setText("Please wait " + secondsRemaining + " seconds...");
            statusLabel.setForeground(COLOR_COOLDOWN);
        });
    }

    /**
     * Update the panel to show "ready" state.
     */
    public void setReadyState()
    {
        SwingUtilities.invokeLater(() -> {
            captureButton.setEnabled(true);
            captureButton.setText("Quick Capture");
            captureButton.setBackground(new Color(93, 173, 226));
            statusLabel.setText("Ready to capture");
            statusLabel.setForeground(COLOR_READY);
        });
    }

    /**
     * Update the panel to show "success" state briefly.
     */
    public void setSuccessState()
    {
        SwingUtilities.invokeLater(() -> {
            captureButton.setEnabled(false);
            captureButton.setText("Sent!");
            captureButton.setBackground(COLOR_READY);
            statusLabel.setText("Capture sent successfully!");
            statusLabel.setForeground(COLOR_READY);
        });
    }

    /**
     * Update the panel to show "error" state.
     */
    public void setErrorState(String message)
    {
        SwingUtilities.invokeLater(() -> {
            captureButton.setEnabled(true);
            captureButton.setText("Quick Capture");
            captureButton.setBackground(new Color(93, 173, 226));
            statusLabel.setText(message);
            statusLabel.setForeground(COLOR_ERROR);
        });
    }

}
