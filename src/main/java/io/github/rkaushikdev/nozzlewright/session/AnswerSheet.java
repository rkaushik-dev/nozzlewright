package io.github.rkaushikdev.nozzlewright.session;

import io.github.rkaushikdev.nozzlewright.LabelError;
import io.github.rkaushikdev.nozzlewright.util.Repr;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What the machine asked for, lined up against what the label says about it.
 *
 * <p>The two disagree more often than they should. A label edited on the machine display grows a
 * field the handheld has never seen, and a label pushed from the desktop tool while a run was up
 * leaves the machine asking for a field the new label does not have. Both are reported rather than
 * guessed at, because a lot code guessed wrong is a recall.
 *
 * @param questions every field the machine is waiting on, in the order it asked
 * @param unknown fields the machine asked for that the label does not declare
 * @param unused prompt fields the label declares that the machine is not asking for, sorted
 */
public record AnswerSheet(List<Question> questions, List<String> unknown, List<String> unused) {

    /** Copies the lists. */
    public AnswerSheet {
        questions = List.copyOf(questions);
        unknown = List.copyOf(unknown);
        unused = List.copyOf(unused);
    }

    /** A sheet with no questions. */
    public AnswerSheet() {
        this(List.of(), List.of(), List.of());
    }

    /** True when the machine and the label agree on the field list. */
    public boolean complete() {
        return unknown.isEmpty() && unused.isEmpty();
    }

    /**
     * One question by name.
     *
     * @throws LabelError when the machine is not asking for it
     */
    public Question question(String name) {
        for (Question item : questions) {
            if (item.name().equals(name)) {
                return item;
            }
        }
        throw new LabelError("the machine is not asking for " + Repr.of(name));
    }

    /**
     * Check a whole set of answers before any of it is sent. A question with no answer is checked
     * with its default.
     *
     * @throws LabelError when an answer is unusable or answers a field the machine is not asking for
     */
    public void check(Map<String, String> answers) {
        for (Question item : questions) {
            item.check(answers.getOrDefault(item.name(), item.defaultValue()));
        }
        TreeSet<String> extra = new TreeSet<>(answers.keySet());
        questions.forEach(item -> extra.remove(item.name()));
        if (!extra.isEmpty()) {
            throw new LabelError("the machine is not asking for: " + String.join(", ", extra));
        }
    }

    /** One line for the log. */
    public String summary() {
        String text = questions.size() + " questions";
        if (!unknown.isEmpty()) {
            text += ", " + unknown.size() + " the label does not have";
        }
        if (!unused.isEmpty()) {
            text += ", " + unused.size() + " the machine is not asking for";
        }
        return text;
    }
}
