package io.github.rkaushikdev.nozzlewright.labels;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The built in font: five wide by seven high.
 *
 * <p>The smallest thing that still reads on a carton after a week in a chiller. This is the font
 * the machine boots with and the one every site falls back to when a custom font fails to load.
 *
 * <p>Patterns are authored as rows separated by a slash, top row first. A hash fires a nozzle and a
 * dot does not. The character set is what coding actually uses: digits, capitals, and the
 * punctuation that appears in a lot code, a date, a best before or a shift marker. Lower case is
 * deliberately absent. It does not survive seven nozzles and no customer has ever asked for it.
 */
public final class FontData {

    private static final Pattern ROW_SEPARATOR = Pattern.compile("/");

    /** Every authored glyph pattern, by character, in authoring order. */
    public static final Map<String, String> PATTERNS = patterns();

    /** The stock font. Immutable once built, so one instance is enough. */
    public static final BitmapFont FIVE_BY_SEVEN = buildFiveBySeven();

    private FontData() {}

    /** The stock font, freshly compiled from {@link #PATTERNS}. */
    public static BitmapFont buildFiveBySeven() {
        Map<String, Glyph> compiled = new LinkedHashMap<>();
        PATTERNS.forEach((character, pattern) ->
                compiled.put(character, Glyph.compile(character, Arrays.asList(ROW_SEPARATOR.split(pattern, -1)))));
        return new BitmapFont("5x7", 7, compiled, 1, "?");
    }

    private static Map<String, String> patterns() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("0", ".###./#...#/#..##/#.#.#/##..#/#...#/.###.");
        table.put("1", "..#../.##../..#../..#../..#../..#../.###.");
        table.put("2", ".###./#...#/....#/...#./..#../.#.../#####");
        table.put("3", "#####/...#./..##./....#/....#/#...#/.###.");
        table.put("4", "...#./..##./.#.#./#..#./#####/...#./...#.");
        table.put("5", "#####/#..../####./....#/....#/#...#/.###.");
        table.put("6", "..##./.#.../#..../####./#...#/#...#/.###.");
        table.put("7", "#####/....#/...#./..#../.#.../.#.../.#...");
        table.put("8", ".###./#...#/#...#/.###./#...#/#...#/.###.");
        table.put("9", ".###./#...#/#...#/.####/....#/...#./.##..");
        table.put("A", ".###./#...#/#...#/#####/#...#/#...#/#...#");
        table.put("B", "####./#...#/#...#/####./#...#/#...#/####.");
        table.put("C", ".###./#...#/#..../#..../#..../#...#/.###.");
        table.put("D", "####./#...#/#...#/#...#/#...#/#...#/####.");
        table.put("E", "#####/#..../#..../####./#..../#..../#####");
        table.put("F", "#####/#..../#..../####./#..../#..../#....");
        table.put("G", ".###./#...#/#..../#.###/#...#/#...#/.###.");
        table.put("H", "#...#/#...#/#...#/#####/#...#/#...#/#...#");
        table.put("I", ".###./..#../..#../..#../..#../..#../.###.");
        table.put("J", "....#/....#/....#/....#/#...#/#...#/.###.");
        table.put("K", "#...#/#..#./#.#../##.../#.#../#..#./#...#");
        table.put("L", "#..../#..../#..../#..../#..../#..../#####");
        table.put("M", "#...#/##.##/#.#.#/#.#.#/#...#/#...#/#...#");
        table.put("N", "#...#/##..#/#.#.#/#..##/#...#/#...#/#...#");
        table.put("O", ".###./#...#/#...#/#...#/#...#/#...#/.###.");
        table.put("P", "####./#...#/#...#/####./#..../#..../#....");
        table.put("Q", ".###./#...#/#...#/#...#/#.#.#/#..#./.##.#");
        table.put("R", "####./#...#/#...#/####./#.#../#..#./#...#");
        table.put("S", ".###./#...#/#..../.###./....#/#...#/.###.");
        table.put("T", "#####/..#../..#../..#../..#../..#../..#..");
        table.put("U", "#...#/#...#/#...#/#...#/#...#/#...#/.###.");
        table.put("V", "#...#/#...#/#...#/#...#/#...#/.#.#./..#..");
        table.put("W", "#...#/#...#/#...#/#.#.#/#.#.#/##.##/#...#");
        table.put("X", "#...#/#...#/.#.#./..#../.#.#./#...#/#...#");
        table.put("Y", "#...#/#...#/.#.#./..#../..#../..#../..#..");
        table.put("Z", "#####/....#/...#./..#../.#.../#..../#####");
        table.put(" ", ".../.../.../.../.../.../...");
        table.put("-", "...../...../...../#####/...../...../.....");
        table.put(".", "...../...../...../...../...../.##../.##..");
        table.put(",", "...../...../...../...../.##../.##../.#...");
        table.put(":", "...../.##../.##../...../.##../.##../.....");
        table.put(";", "...../.##../.##../...../.##../.##../.#...");
        table.put("/", "....#/....#/...#./..#../.#.../#..../#....");
        table.put("\\", "#..../#..../.#.../..#../...#./....#/....#");
        table.put("(", "..##./.#.../#..../#..../#..../.#.../..##.");
        table.put(")", ".##../...#./....#/....#/....#/...#./.##..");
        table.put("*", "...../#.#.#/.###./#####/.###./#.#.#/.....");
        table.put("#", ".#.#./.#.#./#####/.#.#./#####/.#.#./.#.#.");
        table.put("+", "...../..#../..#../#####/..#../..#../.....");
        table.put("=", "...../...../#####/...../#####/...../.....");
        table.put("%", "##..#/##..#/...#./..#../.#.../#..##/#..##");
        table.put("<", "...#./..#../.#.../#..../.#.../..#../...#.");
        table.put(">", ".#.../..#../...#./....#/...#./..#../.#...");
        table.put("?", ".###./#...#/....#/...#./..#../...../..#..");
        table.put("!", "..#../..#../..#../..#../..#../...../..#..");
        table.put("'", "..#../..#../...../...../...../...../.....");
        table.put("\"", ".#.#./.#.#./...../...../...../...../.....");
        return Collections.unmodifiableMap(table);
    }
}
