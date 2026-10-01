package dev.itemexplorer.menu;

/** Logical GUI coordinates, independent of the Minecraft client runtime. */
public record StorageLayout(int width, int height) {
    public static StorageLayout fit(int screenWidth, int screenHeight) {
        return new StorageLayout(Math.max(320, Math.min(440, screenWidth - 12)),
                Math.max(234, Math.min(340, screenHeight - 12)));
    }

    public int craftingX() { return (width - 290) / 2; }
    public int craftingY() { return inventoryY() + 18; }
    public int craftingResultX() { return craftingX() + 94; }
    public int craftingResultY() { return craftingY() + 18; }
    public int inventoryX() { return craftingX() + 128; }
    public int inventoryY() { return height - 84; }
    public int controlsY() { return inventoryY() - 25; }
    public int controlsX() { return (width - 304) / 2; }
    public int browserX() { return 110; }
    public int browserY() { return 58; }
    public int browserWidth() { return width - browserX() - 8; }
    public int browserHeight() { return controlsY() - browserY() - 6; }
    public int columns() { return browserWidth() / 60; }
    public int rows() { return browserHeight() / 30; }
    public int cellWidth() { return browserWidth() / columns(); }
    public int pageSize() { return columns() * rows(); }
    public int searchRows() { return browserHeight() / 30; }
    public int searchWidth() { return width - 164; }
    public int searchScopeX() { return width - 152; }
    public int searchExitX() { return width - 102; }
    public int treeRows() { return browserHeight() / 12; }
    public int modalX() { return (width - 240) / 2; }
    public int modalY() { return (height - 80) / 2; }
}
