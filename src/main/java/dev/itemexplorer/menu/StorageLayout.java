package dev.itemexplorer.menu;

/** Logical GUI coordinates, independent of the Minecraft client runtime. */
public record StorageLayout(int width, int height) {
    public static StorageLayout fit(int screenWidth, int screenHeight) {
        return new StorageLayout(Math.max(320, Math.min(440, screenWidth - 12)),
                Math.max(234, Math.min(340, screenHeight - 12)));
    }

    public int inventoryX() { return (width - 162) / 2; }
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
    public int treeRows() { return browserHeight() / 12; }
    public int modalX() { return (width - 240) / 2; }
    public int modalY() { return (height - 80) / 2; }
}
