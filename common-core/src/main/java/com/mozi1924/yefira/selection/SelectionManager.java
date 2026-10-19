package com.mozi1924.yefira.selection;

import com.mozi1924.yefira.Yefira;
import com.mozi1924.yefira.compat.VersionCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

public class SelectionManager {
    private static final SelectionManager INSTANCE = new SelectionManager();

    public static SelectionManager getInstance() {
        return INSTANCE;
    }

    private BlockPos pos1;
    private BlockPos pos2;
    private ResourceKey<Level> dimension;
    private SelectionBox currentSelection;
    private Level currentLevel;
    private java.nio.file.Path activeStoragePath;

    /**
     * Where the authoritative selection lives for the currently selected sync
     * mode. Server/team mode uses the server-side selection; local export mode
     * uses the client's loaded chunks so a user can export structures from a
     * remote server that does not expose its save file.
     */
    public enum SelectionSource {
        SERVER_AUTHORITATIVE,
        LOCAL_CLIENT
    }

    private volatile SelectionSource selectionSource = SelectionSource.SERVER_AUTHORITATIVE;

    private final List<SelectionChangeListener> listeners = new CopyOnWriteArrayList<>();

    public interface SelectionChangeListener {
        void onSelectionChanged(Level level, SelectionBox selection);
        void onSelectionCleared();
    }

    public void addListener(SelectionChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SelectionChangeListener listener) {
        listeners.remove(listener);
    }

    public synchronized void setActiveStoragePath(java.nio.file.Path storagePath) {
        this.activeStoragePath = storagePath;
    }

    public synchronized void setSelectionSource(SelectionSource source) {
        this.selectionSource = source == null ? SelectionSource.SERVER_AUTHORITATIVE : source;
        // Switching to local export while a client-side preview already exists
        // should immediately publish it to a local WebSocket, if one is running.
        if (this.selectionSource == SelectionSource.LOCAL_CLIENT
                && this.currentSelection != null
                && this.currentLevel != null
                && this.currentLevel.isClientSide()) {
            for (SelectionChangeListener listener : listeners) {
                listener.onSelectionChanged(this.currentLevel, this.currentSelection);
            }
        }
    }

    public synchronized SelectionSource getSelectionSource() {
        return selectionSource;
    }

    public synchronized boolean isLocalClientSelection() {
        return selectionSource == SelectionSource.LOCAL_CLIENT;
    }

    public synchronized java.nio.file.Path getActiveStoragePath() {
        return activeStoragePath;
    }

    public synchronized boolean loadSavedSelection(java.nio.file.Path storagePath, Level level) {
        this.activeStoragePath = storagePath;
        this.currentLevel = level;
        this.dimension = level != null ? level.dimension() : null;
        SelectionStorageManager.SelectionData data = SelectionStorageManager.loadFromPath(storagePath);
        if (data != null) {
            ResourceKey<Level> savedDimension = data.dimension() != null
                    ? data.dimension()
                    : (level != null ? level.dimension() : null);
            if (level != null && savedDimension != null && !savedDimension.equals(level.dimension())) {
                // The saved selection belongs to a dimension that is no longer the
                // active one. Loading it into the wrong level would silently make
                // the scanner read a different dimension's blocks.
                Yefira.LOGGER.warn(
                        "Ignoring saved selection from dimension {} because the active level is {}",
                        VersionCompat.level().getDimensionId(savedDimension),
                        VersionCompat.level().getDimensionId(level));
                this.dimension = level.dimension();
                this.pos1 = null;
                this.pos2 = null;
                this.currentSelection = null;
                for (SelectionChangeListener listener : listeners) {
                    listener.onSelectionCleared();
                }
                return false;
            }
            this.dimension = savedDimension;
            this.pos1 = data.pos1();
            this.pos2 = data.pos2();
            this.currentSelection = new SelectionBox(pos1, pos2);
            for (SelectionChangeListener listener : listeners) {
                listener.onSelectionChanged(currentLevel, currentSelection);
            }
            Yefira.LOGGER.info("Loaded saved selection from {}", storagePath);
            return true;
        } else {
            this.pos1 = null;
            this.pos2 = null;
            this.currentSelection = null;
            for (SelectionChangeListener listener : listeners) {
                listener.onSelectionCleared();
            }
            return false;
        }
    }

    public synchronized void resetOnWorldUnload() {
        this.pos1 = null;
        this.pos2 = null;
        this.currentSelection = null;
        this.currentLevel = null;
        this.dimension = null;
        this.activeStoragePath = null;
        this.selectionSource = SelectionSource.SERVER_AUTHORITATIVE;
        for (SelectionChangeListener listener : listeners) {
            listener.onSelectionCleared();
        }
        Yefira.LOGGER.info("Selection manager reset on world unload.");
    }

    public synchronized void setPos1(Level level, BlockPos pos) {
        prepareForLevel(level);
        this.pos1 = pos;
        updateSelection();
    }

    public synchronized void setPos2(Level level, BlockPos pos) {
        prepareForLevel(level);
        this.pos2 = pos;
        updateSelection();
    }

    public synchronized void setPositions(Level level, BlockPos p1, BlockPos p2) {
        prepareForLevel(level);
        this.pos1 = p1;
        this.pos2 = p2;
        updateSelection();
    }

    /**
     * Keeps both corners in the same dimension. A player can set pos1 in the
     * Nether and pos2 in the Overworld; without this reset the manager would
     * build a coordinate box spanning two dimensions.
     */
    private void prepareForLevel(Level level) {
        ResourceKey<Level> nextDimension = level != null ? level.dimension() : null;
        if (this.dimension != null && nextDimension != null && !this.dimension.equals(nextDimension)) {
            Yefira.LOGGER.info("Dimension changed from {} to {}; clearing the previous selection",
                    VersionCompat.level().getDimensionId(this.dimension),
                    VersionCompat.level().getDimensionId(nextDimension));
            this.pos1 = null;
            this.pos2 = null;
            this.currentSelection = null;
            if (this.activeStoragePath != null) {
                SelectionStorageManager.deleteStorageFile(this.activeStoragePath);
            }
            for (SelectionChangeListener listener : listeners) {
                listener.onSelectionCleared();
            }
        }
        this.currentLevel = level;
        this.dimension = nextDimension;
    }

    public synchronized void clearSelection() {
        this.pos1 = null;
        this.pos2 = null;
        this.currentSelection = null;
        this.currentLevel = null;
        this.dimension = null;
        if (activeStoragePath != null) {
            SelectionStorageManager.deleteStorageFile(activeStoragePath);
        }
        for (SelectionChangeListener listener : listeners) {
            listener.onSelectionCleared();
        }
        Yefira.LOGGER.info("Selection cleared.");
    }

    private void updateSelection() {
        if (pos1 != null && pos2 != null) {
            this.currentSelection = new SelectionBox(pos1, pos2);
            Yefira.LOGGER.info("Selection updated: Min{} Max{} (Volume: {})",
                    currentSelection.getMin().toShortString(),
                    currentSelection.getMax().toShortString(),
                    currentSelection.getVolume());
            if (activeStoragePath != null) {
                SelectionStorageManager.saveToPath(activeStoragePath, pos1, pos2, dimension);
            }
            // In server/team mode a ClientLevel selection is only a render
            // preview; the authoritative selection is applied on the server.
            // In local export mode it is the actual data source, and the local
            // WebSocket scans the client's loaded chunks.
            if (currentLevel != null && currentLevel.isClientSide() && !isLocalClientSelection()) {
                return;
            }
            for (SelectionChangeListener listener : listeners) {
                listener.onSelectionChanged(currentLevel, currentSelection);
            }
        }
    }

    public synchronized SelectionBox getCurrentSelection() {
        return currentSelection;
    }

    public synchronized Level getCurrentLevel() {
        return currentLevel;
    }

    public synchronized ResourceKey<Level> getDimension() {
        return dimension;
    }

    public synchronized boolean hasSelection() {
        return currentSelection != null;
    }

    public synchronized BlockPos getPos1() {
        return pos1;
    }

    public synchronized BlockPos getPos2() {
        return pos2;
    }
}
