package io.github.rkaushikdev.nozzlewright.session;

import io.github.rkaushikdev.nozzlewright.labels.Field;
import io.github.rkaushikdev.nozzlewright.labels.LabelTemplate;
import io.github.rkaushikdev.nozzlewright.labels.PromptField;
import io.github.rkaushikdev.nozzlewright.protocol.Response;
import io.github.rkaushikdev.nozzlewright.telemetry.EventLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Fields the operator has to fill in.
 *
 * <p>A label with a lot code on it cannot print until somebody says what the lot is. The machine knows
 * that: ASKINTER lists the fields it is waiting for and SENDINTER answers one. What it does not do is
 * say anything useful about them, so the prompt an operator sees comes from the label and the list
 * comes from the machine, and {@link #reconcile} lines the two up.
 */
public final class Interactive {

    private static final EventLog LOG = EventLog.get("nozzlewright.interactive");

    private Interactive() {}

    /** The field names the machine is waiting on, in the order it lists them. */
    public static List<String> ask(PrinterSession session) throws InterruptedException {
        Response response = session.command("ASKINTER");
        List<String> names = new ArrayList<>();
        for (Map<String, String> row : response.table()) {
            String name = row.getOrDefault("NAME", "").strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /**
     * Send every answer, after checking the whole set; a question with no answer is sent its default.
     *
     * <p>Checking first matters. Sending three of five answers and then finding the fourth is too long
     * leaves the machine half loaded and the operator with no way to tell which half.
     *
     * @return how many answers were sent
     * @throws io.github.rkaushikdev.nozzlewright.LabelError when any answer is unusable, before anything
     *     is sent
     */
    public static int answer(PrinterSession session, AnswerSheet sheet, Map<String, String> answers)
            throws InterruptedException {
        sheet.check(answers);
        int sent = 0;
        for (Question question : sheet.questions()) {
            session.command(
                    "SENDINTER", question.name(), answers.getOrDefault(question.name(), question.defaultValue()));
            sent++;
        }
        LOG.info("interactive fields answered", "printer", session.printerId(), "fields", sent);
        return sent;
    }

    /** Line up what the machine asked with what the label declares. */
    public static AnswerSheet reconcile(List<String> asked, LabelTemplate template) {
        return reconcile(asked, Optional.of(template));
    }

    /**
     * Line up what the machine asked with what the label declares, if the label is known.
     *
     * <p>Without a label every field is a plain question and every one is unknown.
     */
    public static AnswerSheet reconcile(List<String> asked, Optional<LabelTemplate> template) {
        if (template.isEmpty()) {
            return new AnswerSheet(asked.stream().map(Question::new).toList(), asked, List.of());
        }
        Map<String, Field> declared = new LinkedHashMap<>();
        for (Field item : template.get().interactiveFields()) {
            declared.put(item.name(), item);
        }
        List<Question> questions = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String name : asked) {
            Field found = declared.get(name);
            if (found == null) {
                unknown.add(name);
                questions.add(new Question(name));
                continue;
            }
            questions.add(questionFor(name, found));
        }
        TreeSet<String> unused = new TreeSet<>(declared.keySet());
        unused.removeAll(asked);
        return new AnswerSheet(questions, unknown, List.copyOf(unused));
    }

    /** Turn a declared field into a question, with a prompt field's limits and default. */
    private static Question questionFor(String name, Field declared) {
        String prompt = name.replace("_", " ");
        if (declared instanceof PromptField field) {
            return new Question(name, prompt, field.maxLen(), field.defaultValue(), field.required());
        }
        return new Question(name, prompt, 32, "", true);
    }
}
