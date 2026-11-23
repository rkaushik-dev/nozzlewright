package io.github.rkaushikdev.nozzlewright.labels;

import java.util.List;

/**
 * The rectangle one element covers on the message, in dots, and for a barcode the quiet zone
 * either side of its bars.
 *
 * <p>A barcode's quiet zone is space it owns without printing anything in it. The raster draws
 * elements as a union, so a date code dragged up against a barcode in the desktop tool prints
 * happily into the zone, and the symbol stops scanning without a single bar being wrong.
 *
 * @param element the element name
 * @param x the left column
 * @param y the top row
 * @param width columns covered, quiet zones included
 * @param height rows covered, a human readable line included
 * @param quietZone columns of quiet zone at each end, nought for anything but a barcode
 * @param barHeight rows the quiet zone runs down, which is the bar height
 */
public record Footprint(String element, int x, int y, int width, int height, int quietZone, int barHeight) {

    /** A plain rectangle with no quiet zone. */
    public static Footprint of(String element, int x, int y, int width, int height) {
        return new Footprint(element, x, y, width, height, 0, 0);
    }

    /** True when the two rectangles share at least one dot. */
    public boolean overlaps(Footprint other) {
        return overlaps(other.x, other.y, other.width, other.height);
    }

    /** True when some dot of {@code other} lands in one of this element's quiet zones. */
    public boolean intrudedBy(Footprint other) {
        if (quietZone == 0) {
            return false;
        }
        return quietZones().stream().anyMatch(zone -> zone.overlaps(other.x, other.y, other.width, other.height));
    }

    /** The two quiet zone strips, left then right. */
    public List<Footprint> quietZones() {
        return List.of(
                of(element, x, y, quietZone, barHeight), of(element, x + width - quietZone, y, quietZone, barHeight));
    }

    private boolean overlaps(int otherX, int otherY, int otherWidth, int otherHeight) {
        return width > 0
                && height > 0
                && otherWidth > 0
                && otherHeight > 0
                && x < otherX + otherWidth
                && otherX < x + width
                && y < otherY + otherHeight
                && otherY < y + height;
    }
}
