package com.mozi1924.yefira.selection;

import com.mozi1924.yefira.Yefira;
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

    public synchronized java.nio.file.Path getActiveStoragePath() {
        return activeStoragePath;
    }

    public synchronized boolean loadSavedSelection(java.nio.file.Path storagePath, Level level) {
        this.activeStoragePath = storagePath;
        this.currentLevel = level;
        this.dimension = level != null ? level.dimension() : null;
        SelectionStorageManager.SelectionData data = SelectionStorageManager.loadFromPath(storagePath);
        if (data != null) {
            this.dimension = data.dimension() != null
                    ? data.dimension()
                    : (level != null ? level.dimension() : null);
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
        for (SelectionChangeListener listener : listeners) {
            listener.onSelectionCleared();
        }
        Yefira.LOGGER.info("Selection manager reset on world unload.");
    }

    public synchronized void setPos1(Level level, BlockPos pos) {
        this.currentLevel = level;
        this.dimension = level.dimension();
        this.pos1 = pos;
        updateSelection();
    }

    public synchronized void setPos2(Level level, BlockPos pos) {
        this.currentLevel = level;
        this.dimension = level.dimension();
        this.pos2 = pos;
        updateSelection();
    }

    public synchronized void setPositions(Level level, BlockPos p1, BlockPos p2) {
        this.currentLevel = level;
        this.dimension = level.dimension();
        this.pos1 = p1;
        this.pos2 = p2;
        updateSelection();
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
            // A ClientLevel selection is a render-only preview. The authoritative
            // selection is applied on the server (via /yefira box) so the scanner
            // never reads ClientLevel chunks and multiplayer clients cannot
            // silently diverge from the dedicated server.
            if (currentLevel != null && currentLevel.isClientSide()) {
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
