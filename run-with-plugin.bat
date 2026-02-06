@echo off
setlocal enabledelayedexpansion

REM Script to sync plugin from osrs-tracker, build, and run RuneLite on Windows

echo.
echo ========================================
echo  OSRS Tracker Plugin - Build ^& Run
echo ========================================
echo.

REM Define directories
set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
for %%I in ("%SCRIPT_DIR%\..") do set "BASE_DIR=%%~fI"

set "OSRS_TRACKER_PLUGIN=%BASE_DIR%\osrs-tracker-plugin\src\main\java\com\osrstracker"
set "OSRS_TRACKER_RESOURCES=%BASE_DIR%\osrs-tracker-plugin\src\main\resources\com\osrstracker"
set "RUNELITE_PLUGIN=%SCRIPT_DIR%\runelite-client\src\main\java\net\runelite\client\plugins\osrstracker"
set "RUNELITE_RESOURCES=%SCRIPT_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins\osrstracker"

REM Step 1: Check if osrs-tracker plugin exists
echo [Step 1] Checking source files...
if not exist "%OSRS_TRACKER_PLUGIN%" (
    echo ERROR: osrs-tracker plugin not found at %OSRS_TRACKER_PLUGIN%
    echo Make sure you have cloned osrs-tracker-plugin next to runelite
    pause
    exit /b 1
)
echo OK: Source plugin found
echo.

REM Step 2: Sync plugin files
echo [Step 2] Syncing plugin files...

REM Clear and create directories
if exist "%RUNELITE_PLUGIN%" rmdir /s /q "%RUNELITE_PLUGIN%"
mkdir "%RUNELITE_PLUGIN%"
mkdir "%RUNELITE_PLUGIN%\api"
mkdir "%RUNELITE_PLUGIN%\video"
mkdir "%RUNELITE_PLUGIN%\death"
mkdir "%RUNELITE_PLUGIN%\collectionlog"
mkdir "%RUNELITE_PLUGIN%\loot"
mkdir "%RUNELITE_PLUGIN%\quest"
mkdir "%RUNELITE_PLUGIN%\skills"
mkdir "%RUNELITE_PLUGIN%\clue"
mkdir "%RUNELITE_PLUGIN%\itemsnitch"
mkdir "%RUNELITE_PLUGIN%\bingo"
mkdir "%RUNELITE_PLUGIN%\pets"

REM Copy and transform files using PowerShell for sed-like replacement
call :transform "%OSRS_TRACKER_PLUGIN%\OsrsTrackerPlugin.java" "%RUNELITE_PLUGIN%\OsrsTrackerPlugin.java"
call :transform "%OSRS_TRACKER_PLUGIN%\OsrsTrackerConfig.java" "%RUNELITE_PLUGIN%\OsrsTrackerConfig.java"
call :transform "%OSRS_TRACKER_PLUGIN%\OsrsTrackerPanel.java" "%RUNELITE_PLUGIN%\OsrsTrackerPanel.java"
call :transform "%OSRS_TRACKER_PLUGIN%\OsrsTrackerQuestData.java" "%RUNELITE_PLUGIN%\OsrsTrackerQuestData.java"
call :transform "%OSRS_TRACKER_PLUGIN%\api\ApiClient.java" "%RUNELITE_PLUGIN%\api\ApiClient.java"
call :transform "%OSRS_TRACKER_PLUGIN%\api\ScreenshotUploadService.java" "%RUNELITE_PLUGIN%\api\ScreenshotUploadService.java"
call :transform "%OSRS_TRACKER_PLUGIN%\video\VideoQuality.java" "%RUNELITE_PLUGIN%\video\VideoQuality.java"
call :transform "%OSRS_TRACKER_PLUGIN%\video\VideoRecorder.java" "%RUNELITE_PLUGIN%\video\VideoRecorder.java"
call :transform "%OSRS_TRACKER_PLUGIN%\death\DeathTracker.java" "%RUNELITE_PLUGIN%\death\DeathTracker.java"
call :transform "%OSRS_TRACKER_PLUGIN%\collectionlog\CollectionLogTracker.java" "%RUNELITE_PLUGIN%\collectionlog\CollectionLogTracker.java"
call :transform "%OSRS_TRACKER_PLUGIN%\loot\LootTracker.java" "%RUNELITE_PLUGIN%\loot\LootTracker.java"
call :transform "%OSRS_TRACKER_PLUGIN%\quest\QuestTracker.java" "%RUNELITE_PLUGIN%\quest\QuestTracker.java"
call :transform "%OSRS_TRACKER_PLUGIN%\skills\SkillLevelTracker.java" "%RUNELITE_PLUGIN%\skills\SkillLevelTracker.java"
call :transform "%OSRS_TRACKER_PLUGIN%\clue\ClueScrollTracker.java" "%RUNELITE_PLUGIN%\clue\ClueScrollTracker.java"
call :transform "%OSRS_TRACKER_PLUGIN%\itemsnitch\SharedItem.java" "%RUNELITE_PLUGIN%\itemsnitch\SharedItem.java"
call :transform "%OSRS_TRACKER_PLUGIN%\itemsnitch\BankItemSighting.java" "%RUNELITE_PLUGIN%\itemsnitch\BankItemSighting.java"
call :transform "%OSRS_TRACKER_PLUGIN%\itemsnitch\ItemSnitchTracker.java" "%RUNELITE_PLUGIN%\itemsnitch\ItemSnitchTracker.java"
call :transform "%OSRS_TRACKER_PLUGIN%\itemsnitch\ItemSnitchBankOverlay.java" "%RUNELITE_PLUGIN%\itemsnitch\ItemSnitchBankOverlay.java"
call :transform "%OSRS_TRACKER_PLUGIN%\itemsnitch\ItemSnitchButton.java" "%RUNELITE_PLUGIN%\itemsnitch\ItemSnitchButton.java"
call :transform "%OSRS_TRACKER_PLUGIN%\itemsnitch\ItemSnitchSprites.java" "%RUNELITE_PLUGIN%\itemsnitch\ItemSnitchSprites.java"
call :transform "%OSRS_TRACKER_PLUGIN%\itemsnitch\ItemSnitchVerbosity.java" "%RUNELITE_PLUGIN%\itemsnitch\ItemSnitchVerbosity.java"
call :transform "%OSRS_TRACKER_PLUGIN%\bingo\BingoSubscription.java" "%RUNELITE_PLUGIN%\bingo\BingoSubscription.java"
call :transform "%OSRS_TRACKER_PLUGIN%\bingo\BingoSubscriptionManager.java" "%RUNELITE_PLUGIN%\bingo\BingoSubscriptionManager.java"
call :transform "%OSRS_TRACKER_PLUGIN%\bingo\BingoProgressReporter.java" "%RUNELITE_PLUGIN%\bingo\BingoProgressReporter.java"
call :transform "%OSRS_TRACKER_PLUGIN%\pets\PetTracker.java" "%RUNELITE_PLUGIN%\pets\PetTracker.java"
call :transform "%OSRS_TRACKER_PLUGIN%\pets\PetDropData.java" "%RUNELITE_PLUGIN%\pets\PetDropData.java"

echo OK: Plugin files synced
echo.

REM Copy resources
echo [Step 2b] Syncing resources...
if exist "%OSRS_TRACKER_RESOURCES%" (
    if not exist "%RUNELITE_RESOURCES%" mkdir "%RUNELITE_RESOURCES%"
    xcopy /s /y /q "%OSRS_TRACKER_RESOURCES%\*" "%RUNELITE_RESOURCES%\" >nul 2>&1
    echo OK: Resources synced
)

REM Copy itemsnitch resources
set "ITEMSNITCH_RESOURCES=%BASE_DIR%\osrs-tracker-plugin\src\main\resources\com\osrstracker\itemsnitch"
set "ITEMSNITCH_RESOURCES_DEST=%SCRIPT_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins\osrstracker\itemsnitch"
if exist "%ITEMSNITCH_RESOURCES%" (
    if not exist "%ITEMSNITCH_RESOURCES_DEST%" mkdir "%ITEMSNITCH_RESOURCES_DEST%"
    xcopy /s /y /q "%ITEMSNITCH_RESOURCES%\*" "%ITEMSNITCH_RESOURCES_DEST%\" >nul 2>&1
    echo OK: Item Snitch sprites synced
)
echo.

REM Step 3: Build RuneLite
echo [Step 3] Building RuneLite...
echo This may take a few minutes on first run...
echo.

cd /d "%SCRIPT_DIR%"
call gradlew.bat :client:shadowJar
if errorlevel 1 (
    echo ERROR: Build failed!
    pause
    exit /b 1
)

REM Find the shadow JAR
for /f "delims=" %%i in ('dir /b /s "%SCRIPT_DIR%\runelite-client\build\libs\*-shaded.jar" 2^>nul') do set "SHADOW_JAR=%%i"

if not defined SHADOW_JAR (
    echo ERROR: Could not find shadow JAR. Build may have failed.
    pause
    exit /b 1
)

echo.
echo OK: Build complete!
echo JAR: %SHADOW_JAR%
echo.

REM Step 4: Launch RuneLite
echo [Step 4] Launching RuneLite...
echo.
echo After RuneLite launches:
echo   1. Click the wrench icon (Configuration)
echo   2. Search for 'OSRS Tracker'
echo   3. Add your API token from Settings - API Tokens
echo.

java -jar "%SHADOW_JAR%"
goto :eof

REM Function to copy and transform package names
:transform
set "SRC=%~1"
set "DEST=%~2"
if exist "%SRC%" (
    powershell -Command "(Get-Content '%SRC%') -replace 'package com\.osrstracker', 'package net.runelite.client.plugins.osrstracker' -replace 'import com\.osrstracker', 'import net.runelite.client.plugins.osrstracker' | Set-Content '%DEST%'"
    for %%F in ("%SRC%") do echo   - %%~nxF
)
goto :eof
