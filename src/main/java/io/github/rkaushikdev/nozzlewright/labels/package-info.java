/**
 * Label templates, the dynamic fields they draw from, the stored text format, the dot matrix
 * fonts, the two barcodes, and turning all of it into the columns a head fires.
 *
 * <p>The pieces stack. {@link io.github.rkaushikdev.nozzlewright.labels.Field} and {@link
 * io.github.rkaushikdev.nozzlewright.labels.FieldContext} say what text a hole in a label holds
 * right now. {@link io.github.rkaushikdev.nozzlewright.labels.LabelTemplate} places elements on a
 * grid of dots, reads and writes the stored format, and resolves its fields into a {@link
 * io.github.rkaushikdev.nozzlewright.labels.ResolvedLabel}. {@link
 * io.github.rkaushikdev.nozzlewright.labels.Raster} draws that into a {@link
 * io.github.rkaushikdev.nozzlewright.labels.DotMatrix}, which {@link
 * io.github.rkaushikdev.nozzlewright.labels.PrintPlan} splits across the pens of a group. Around
 * them sit the checks a person runs before a label goes near a product: {@link
 * io.github.rkaushikdev.nozzlewright.labels.Lint}, {@link
 * io.github.rkaushikdev.nozzlewright.labels.Proof}, {@link
 * io.github.rkaushikdev.nozzlewright.labels.LabelDiff} and {@link
 * io.github.rkaushikdev.nozzlewright.labels.Verification}. {@link
 * io.github.rkaushikdev.nozzlewright.labels.LabelStore} keeps labels on disk.
 */
package io.github.rkaushikdev.nozzlewright.labels;
