package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.labels.FontStore;
import io.github.rkaushikdev.nozzlewright.labels.LabelDiff;
import io.github.rkaushikdev.nozzlewright.labels.LabelStore;
import io.github.rkaushikdev.nozzlewright.labels.LabelTemplate;
import io.github.rkaushikdev.nozzlewright.labels.Lint;
import io.github.rkaushikdev.nozzlewright.printer.HeadGeometry;
import io.github.rkaushikdev.nozzlewright.storage.LabelRepo;
import io.github.rkaushikdev.nozzlewright.storage.LabelRevision;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything under {@code /labels}: the site label store, versioned.
 *
 * <p>Labels are stored as source text and parsed on the way in, so a label that cannot be parsed never
 * reaches the database and a client finds out at the moment it uploads rather than at the moment a
 * line tries to print it. With {@code lint=true} a store also refuses a label that parses and still
 * cannot print on a head of {@code pens} pens at {@code dpi}.
 */
@RestController
@RequiresKey
@RequestMapping("/labels")
@Tag(name = "labels")
public class LabelRoutes {

    private final Services services;

    /** The routes over one services object. */
    public LabelRoutes(Services services) {
        this.services = services;
    }

    /** The newest revision of every label, by name. */
    @GetMapping
    public List<LabelOut> listLabels() {
        return services.database().apply(unit -> {
            LabelRepo repo = new LabelRepo(unit);
            List<LabelOut> out = new ArrayList<>();
            for (String name : repo.names()) {
                repo.latest(name).ifPresent(latest -> out.add(LabelOut.from(latest)));
            }
            return out;
        });
    }

    /** The newest revision of one label, source included. */
    @GetMapping("/{name}")
    public LabelOut getLabel(@PathVariable("name") String name) {
        return services.database().apply(unit -> new LabelRepo(unit)
                .latest(name)
                .map(latest -> LabelOut.from(latest, true))
                .orElseThrow(() -> noSuchLabel(name)));
    }

    /**
     * Every revision of one label, oldest first.
     *
     * <p>Paged because this one grows for ever. A label somebody edits every week for three years is a
     * hundred and fifty revisions, all of them carrying their whole source.
     */
    @GetMapping("/{name}/revisions")
    public Page<LabelOut> listRevisions(@PathVariable("name") String name, PageParams paging) {
        return services.database().apply(unit -> {
            List<LabelRevision> rows = new LabelRepo(unit).revisions(name);
            if (rows.isEmpty()) {
                throw noSuchLabel(name);
            }
            return Page.ofAll(rows.stream().map(LabelOut::from).toList(), paging);
        });
    }

    /**
     * Compare two stored revisions of one label: by default the previous one and the latest.
     *
     * <p>With no revisions named it compares the last two, which is the question somebody asks when a
     * line started printing something different this morning and nobody will say what they changed.
     */
    @GetMapping("/{name}/diff")
    public DiffOut diffRevisions(
            @PathVariable("name") String name,
            @RequestParam(name = "left", required = false) Integer left,
            @RequestParam(name = "right", required = false) Integer right) {
        if (left != null) {
            QueryValues.within("left", left, 1, Integer.MAX_VALUE);
        }
        if (right != null) {
            QueryValues.within("right", right, 1, Integer.MAX_VALUE);
        }
        return services.database().apply(unit -> {
            Map<Integer, LabelRevision> rows = new TreeMap<>();
            for (LabelRevision row : new LabelRepo(unit).revisions(name)) {
                rows.put(row.getRevision(), row);
            }
            if (rows.isEmpty()) {
                throw noSuchLabel(name);
            }
            List<Integer> numbers = new ArrayList<>(rows.keySet());
            if (numbers.size() < 2 && (left == null || right == null)) {
                throw new ApiProblem(409, name + " has only one revision, there is nothing to compare");
            }
            int older = left != null ? left : numbers.get(numbers.size() - 2);
            int newer = right != null ? right : numbers.get(numbers.size() - 1);
            for (int wanted : List.of(older, newer)) {
                if (!rows.containsKey(wanted)) {
                    throw new ApiProblem(404, name + " has no revision " + wanted);
                }
            }
            LabelDiff result = LabelDiff.between(
                    LabelTemplate.parse(rows.get(older).getSource()),
                    LabelTemplate.parse(rows.get(newer).getSource()));
            return DiffOut.from(result, older, newer);
        });
    }

    /**
     * Store a label. Parsing it is the validation.
     *
     * <p>The source is written back out in its normal form before it is hashed, so an upload that only
     * differs in spacing does not make a revision nobody asked for.
     */
    @PutMapping("/{name}")
    @ResponseStatus(HttpStatus.CREATED)
    public LabelOut putLabel(
            @PathVariable("name") String name,
            @Valid @RequestBody LabelIn body,
            @RequestParam(name = "lint", defaultValue = "false") boolean lint,
            @RequestParam(name = "pens", defaultValue = "1") int pens,
            @RequestParam(name = "dpi", defaultValue = "300") int dpi) {
        LabelTemplate template = LabelTemplate.parse(body.source());
        if (!template.name().equals(name)) {
            throw new ApiProblem(400, "the label calls itself " + template.name() + " and the path says " + name);
        }
        if (lint) {
            QueryValues.within("pens", pens, 1, 8);
            HeadGeometry head = HeadGeometry.stacked(HeadGeometry.DESK_FAMILY, pens, dpi);
            head.validate();
            Lint.check(template, FontStore.stock(), head, LocalDateTime.now()).requirePrintable();
        }
        String source = template.toSource();
        return services.database().apply(unit -> {
            LabelRevision row = new LabelRepo(unit)
                    .save(
                            template.name(),
                            source,
                            LabelStore.contentHash(source),
                            template.heightDots(),
                            !template.interactiveFields().isEmpty(),
                            "");
            return LabelOut.from(row, true);
        });
    }

    private static ApiProblem noSuchLabel(String name) {
        return new ApiProblem(404, "no label called " + name);
    }
}
