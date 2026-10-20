package com.obliterateharness.api;

import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.Views;
import com.obliterateharness.harness.ContextService;
import com.obliterateharness.i18n.I18n;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Contexts: the domains the platform runs (built-in from YAML, or created by people and LLMs). */
@RestController
@RequestMapping("/api/contexts")
public class ContextController {

    public record ContextInput(String name, String description, String icon, String color, String locale, String baseContext, String basePreset,
                               String instructions, Map<String, String> spaceLabels, List<String> tabs) {}

    public record ProjectInput(String name, String description, String locale, String preset, Boolean requireReviewForLlm) {}

    private final ContextService contexts;

    public ContextController(ContextService contexts) {
        this.contexts = contexts;
    }

    @GetMapping
    public List<ContextService.ContextView> list() {
        return contexts.list(I18n.lang());
    }

    @GetMapping("/{id}")
    public ContextService.ContextView get(@PathVariable String id) {
        return contexts.get(id, I18n.lang());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ContextService.ContextView create(@RequestBody ContextInput in) {
        var c = contexts.create(in.name(), in.description(), in.icon(), in.color(), in.locale(), in.baseContext(), in.basePreset(), in.instructions(), Actor.human);
        if (in.spaceLabels() != null || in.tabs() != null) {
            return contexts.update(c.id(), null, null, null, null, null, in.spaceLabels(), in.tabs(), I18n.lang());
        }
        return c;
    }

    @PatchMapping("/{id}")
    public ContextService.ContextView update(@PathVariable String id, @RequestBody ContextInput in) {
        return contexts.update(id, in.name(), in.description(), in.icon(), in.color(), in.instructions(), in.spaceLabels(), in.tabs(), I18n.lang());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @RequestParam String confirm) {
        contexts.delete(id, confirm, I18n.lang());
    }

    /** Creates a project in the context (with the context's types, relations, rules and directives). */
    @PostMapping("/{id}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Views.Project createProject(@PathVariable String id, @RequestBody ProjectInput in) {
        return contexts.createProject(id, in.name(), in.description(), in.locale(), in.preset(), in.requireReviewForLlm(), Actor.human);
    }
}
