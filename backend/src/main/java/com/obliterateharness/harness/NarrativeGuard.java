package com.obliterateharness.harness;

import com.obliterateharness.domain.DomainException;
import com.obliterateharness.service.ProjectService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import static com.obliterateharness.i18n.I18n.t;

/**
 * The narrative engine (units, manuscript, structure, time, templates, hooks) only works in projects of the narrative
 * context or a context based on it. Elsewhere its features answer with a clear error instead of an empty result,
 * so an LLM does not take "nothing there" for an answer.
 */
@Component
public class NarrativeGuard {
    private final ObjectProvider<ContextService> contexts;
    private final ProjectService projects;

    public NarrativeGuard(ObjectProvider<ContextService> contexts, ProjectService projects) {
        this.contexts = contexts;
        this.projects = projects;
    }

    /** Fails unless the narrative engine is on for the project; {@code feature} names what was asked (e.g. writing_context). */
    public void require(String projectId, String feature) {
        if (contexts.getObject().narrativeOn(projectId)) return;
        String context = projects.get(projectId).context();
        throw new DomainException("'" + feature + t("' belongs to the narrative engine and only works in projects of the narrative context "
                        + "(or a context based on it). This project is in the context '",
                "' faz parte do motor narrativo e só funciona em projetos do contexto narrativo (ou de um contexto baseado nele). "
                        + "Este projeto está no contexto '") + context + "'.");
    }
}
