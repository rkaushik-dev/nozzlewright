package io.github.rkaushikdev.nozzlewright.labels;

/**
 * How a {@link Proof} is made and measured.
 *
 * @param horizontalDpi the firing rate the width in millimetres is measured against
 * @param dropVolumePl the drop volume the ink figure is charged at, in picolitres
 * @param raster how the whole message is treated once it is drawn
 * @param maxWidth the widest picture drawn, in columns, nought for no limit
 */
public record ProofOptions(int horizontalDpi, double dropVolumePl, RasterOptions raster, int maxWidth) {

    /** 300 dpi, six picolitre drops, default raster options, trimmed at {@link Proof#MAX_PROOF_WIDTH}. */
    public static ProofOptions defaults() {
        return new ProofOptions(300, 6.0, RasterOptions.defaults(), Proof.MAX_PROOF_WIDTH);
    }

    /** The same options measured at {@code value} dpi. */
    public ProofOptions withHorizontalDpi(int value) {
        return new ProofOptions(value, dropVolumePl, raster, maxWidth);
    }

    /** The same options charging {@code value} picolitre drops. */
    public ProofOptions withDropVolumePl(double value) {
        return new ProofOptions(horizontalDpi, value, raster, maxWidth);
    }

    /** The same options with other raster options. */
    public ProofOptions withRaster(RasterOptions value) {
        return new ProofOptions(horizontalDpi, dropVolumePl, value, maxWidth);
    }

    /** The same options trimming the picture at {@code value} columns, nought for never. */
    public ProofOptions withMaxWidth(int value) {
        return new ProofOptions(horizontalDpi, dropVolumePl, raster, value);
    }
}
