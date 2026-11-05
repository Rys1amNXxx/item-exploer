package dev.itemexplorer.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A collapsible folder list drawn in a recess, matching the terminal's directory pane: 12px rows, wheel
 * scrolling and a scrollbar. Coordinates are absolute screen positions.
 */
final class FolderTree {
    static final int ROW = 12;
    /** click() results that are not folder ids; ids are never negative. */
    static final int MISSED = -1, TOGGLED = -2;
    private static final int MAX_DEPTH = 8;

    record Node(int id, int parent, String name) {}
    private record Row(Node node, int depth) {}

    private List<Node> nodes = List.of();
    private List<Row> rows = List.of();
    private final Set<Integer> collapsed = new HashSet<>(), parents = new HashSet<>();
    private int left, top, width, visible = 1, scroll;

    void place(int left, int top, int width, int visibleRows) {
        this.left = left; this.top = top; this.width = width; visible = Math.max(1, visibleRows);
        rebuild();
    }

    int height() { return visible * ROW; }
    boolean isEmpty() { return nodes.isEmpty(); }
    boolean contains(int id) { return node(id) != null; }

    /** Roots are nodes whose parent is not in the list; collapse state survives updates of the same tree. */
    void setNodes(List<Node> next) {
        nodes = List.copyOf(next);
        parents.clear();
        for (Node node : nodes) parents.add(node.parent());
        collapsed.removeIf(id -> !contains(id));
        rebuild();
    }

    /** Names from the root down, or null for an unknown folder. */
    String path(int id) {
        List<String> names = new ArrayList<>();
        Node cursor = node(id);
        for (int i = 0; cursor != null && i <= MAX_DEPTH; i++) { names.add(cursor.name()); cursor = node(cursor.parent()); }
        if (names.isEmpty()) return null;
        Collections.reverse(names);
        return String.join(" / ", names);
    }

    /** Expands every ancestor and scrolls the folder into view. */
    void reveal(int id) {
        Node cursor = node(id);
        for (int i = 0; cursor != null && i <= MAX_DEPTH; i++) { collapsed.remove(cursor.id()); cursor = node(cursor.parent()); }
        rebuild();
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).node().id() == id) {
            if (i < scroll) scroll = i;
            if (i >= scroll + visible) scroll = i - visible + 1;
        }
    }

    /** selected gets the white highlight; marked gets the green stripe for the setting already in effect. */
    void render(GuiGraphics g, Font font, int selected, int marked, int mouseX, int mouseY, boolean interactive) {
        VanillaGui.recess(g, left, top, width, height());
        Row hovered = interactive ? rowAt(mouseX, mouseY) : null;
        for (int i = 0; i < visible && i + scroll < rows.size(); i++) {
            Row row = rows.get(i + scroll);
            int y = top + i * ROW, id = row.node().id();
            int overlay = id == selected ? 0x66ffffff : hovered == row ? 0x33ffffff : 0;
            if (overlay != 0) g.fill(left, y, left + width - 2, y + ROW, overlay);
            if (id == marked) g.fill(left, y + 1, left + 2, y + ROW - 1, VanillaGui.recessText(VanillaGui.Tone.SUCCESS));
            int x = indent(row);
            if (parents.contains(id)) VanillaGui.recessString(g, font, collapsed.contains(id) ? ">" : "v", x, y + 2, 0xffeeeeee, overlay);
            VanillaGui.folderIcon(g, x + 8, y + 2, true);
            VanillaGui.recessString(g, font, VanillaGui.fit(font, row.node().name(), left + width - 4 - (x + 20)), x + 20, y + 2, 0xffeeeeee, overlay);
        }
        if (rows.size() > visible) {
            int thumb = Math.max(6, height() * visible / rows.size());
            int y = top + (height() - thumb) * scroll / (rows.size() - visible);
            g.fill(left + width - 2, y, left + width, y + thumb, 0x99ffffff);
        }
    }

    /** The full path of the hovered folder, for a tooltip; null over empty space. */
    String pathAt(double x, double y) {
        Row row = rowAt(x, y);
        return row == null ? null : path(row.node().id());
    }

    /** A folder id, TOGGLED when the click opened or closed a branch, or MISSED. */
    int click(double x, double y) {
        Row row = rowAt(x, y);
        if (row == null) return MISSED;
        int id = row.node().id();
        if (parents.contains(id) && x < indent(row) + 7) {
            if (!collapsed.add(id)) collapsed.remove(id);
            rebuild();
            return TOGGLED;
        }
        return id;
    }

    boolean scroll(double x, double y, double delta) {
        if (!inside(x, y)) return false;
        scroll = Math.max(0, Math.min(Math.max(0, rows.size() - visible), scroll + (delta > 0 ? -1 : 1)));
        return true;
    }

    private boolean inside(double x, double y) {
        return x >= left && x < left + width && y >= top && y < top + height();
    }

    private Row rowAt(double x, double y) {
        if (!inside(x, y)) return null;
        int index = scroll + ((int) y - top) / ROW;
        return index < rows.size() ? rows.get(index) : null;
    }

    private int indent(Row row) { return left + 2 + Math.min(row.depth(), 6) * 5; }

    private Node node(int id) {
        for (Node node : nodes) if (node.id() == id) return node;
        return null;
    }

    private void rebuild() {
        List<Row> result = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (Node node : nodes) ids.add(node.id());
        for (Node node : nodes) if (!ids.contains(node.parent())) append(result, node, 0, new HashSet<>());
        rows = result;
        scroll = Math.max(0, Math.min(scroll, rows.size() - visible));
    }

    private void append(List<Row> result, Node node, int depth, Set<Integer> path) {
        // Depth cap and the path set keep a malformed (cyclic) folder list from recursing forever.
        if (depth > MAX_DEPTH || !path.add(node.id())) return;
        result.add(new Row(node, depth));
        if (!collapsed.contains(node.id()))
            for (Node child : nodes) if (child.parent() == node.id()) append(result, child, depth + 1, path);
        path.remove(node.id());
    }
}
