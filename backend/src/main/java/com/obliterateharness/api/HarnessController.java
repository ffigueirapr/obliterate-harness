package com.obliterateharness.api;

import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.Views;
import com.obliterateharness.harness.GuidanceService;
import com.obliterateharness.harness.HarnessLibrary;
import com.obliterateharness.harness.ProfileService;
import com.obliterateharness.i18n.I18n;
import com.obliterateharness.service.ChangesetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Harness endpoints: schema presets of the built-in contexts and project directives. */
@RestController
@RequestMapping("/api")
public class HarnessController {

    public record PresetInput(String profileId) {}

    private final HarnessLibrary library;
    private final ProfileService profiles;
    private final GuidanceService guidance;

    public HarnessController(HarnessLibrary library, ProfileService profiles, GuidanceService guidance) {
        this.library = library;
        this.profiles = profiles;
        this.guidance = guidance;
    }

    /** Schema presets available to the built-in contexts (in the UI language). */
    @GetMapping("/harness/presets")
    public List<HarnessLibrary.ProfileSummary> presets() {
        return library.profiles(I18n.lang());
    }

    /** Adds what a preset defines and the project lacks (types, relations, attributes); removes nothing. */
    @PostMapping("/projects/{pid}/preset/apply")
    public ChangesetService.Proposal applyPreset(@PathVariable String pid, @RequestBody PresetInput in) {
        return profiles.apply(pid, in.profileId(), Actor.human);
    }

    @GetMapping("/projects/{pid}/directives")
    public List<Views.Directive> directives(@PathVariable String pid) {
        return guidance.all(pid);
    }
}
