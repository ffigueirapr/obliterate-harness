package com.obliterateharness.api;

import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.Views;
import com.obliterateharness.harness.ExportService;
import com.obliterateharness.harness.GuidanceService;
import com.obliterateharness.harness.HarnessLibrary;
import com.obliterateharness.harness.NarrativeService;
import com.obliterateharness.harness.ProfileService;
import com.obliterateharness.harness.TemplateService;
import com.obliterateharness.harness.TextDiff;
import com.obliterateharness.harness.UnitService;
import com.obliterateharness.i18n.I18n;
import com.obliterateharness.service.ChangesetService;
import com.obliterateharness.service.ProjectService;
import com.obliterateharness.store.TextVersionRepository;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Harness endpoints: profiles, templates, coverage, hooks, writing context, directives, manuscript text, cast, validation and export. */
@RestController
@RequestMapping("/api")
public class HarnessController {

    public record ProfileInput(String profileId) {}

    public record TemplateInput(String templateId, String unitType, Integer plannedUnits, String scopeUnitId) {}

    public record DeriveInput(String fromProjectId, String unitType, String name, String description, Boolean includeChildren) {}

    public record TextInput(String text, String mode, String attribute, String separator, String note) {}

    public record CastInput(List<String> nodeIds, String mode) {}

    public record ValidateInput(String text) {}

    private final HarnessLibrary library;
    private final ProfileService profiles;
    private final TemplateService templates;
    private final NarrativeService narrative;
    private final GuidanceService guidance;
    private final UnitService units;
    private final ExportService export;
    private final ProjectService projects;

    public HarnessController(HarnessLibrary library, ProfileService profiles, TemplateService templates, NarrativeService narrative,
                             GuidanceService guidance, UnitService units, ExportService export, ProjectService projects) {
        this.library = library;
        this.profiles = profiles;
        this.templates = templates;
        this.narrative = narrative;
        this.guidance = guidance;
        this.units = units;
        this.export = export;
        this.projects = projects;
    }

    // ---------- library (in the UI language) ----------

    @GetMapping("/harness/profiles")
    public List<HarnessLibrary.ProfileSummary> profiles() {
        return library.profiles(I18n.lang());
    }

    @GetMapping("/harness/templates")
    public List<HarnessLibrary.TemplateSummary> templateLibrary() {
        return library.templates(I18n.lang());
    }

    @GetMapping("/harness/templates/{id}")
    public HarnessLibrary.TemplateDef template(@PathVariable String id) {
        return library.requireTemplate(id, I18n.lang());
    }

    @PostMapping("/harness/templates/derive")
    public HarnessLibrary.TemplateDef derive(@RequestBody DeriveInput in) {
        return templates.derive(in.fromProjectId(), in.unitType(), in.name(), in.description(), Boolean.TRUE.equals(in.includeChildren()));
    }

    @PutMapping("/harness/templates")
    public HarnessLibrary.TemplateDef upsertTemplate(@RequestBody HarnessLibrary.TemplateDef in) {
        return templates.upsert(in);
    }

    @DeleteMapping("/harness/templates/{id}")
    public void deleteTemplate(@PathVariable String id) {
        templates.delete(id);
    }

    // ---------- per project ----------

    @PostMapping("/projects/{pid}/profile/apply")
    public ChangesetService.Proposal applyProfile(@PathVariable String pid, @RequestBody ProfileInput in) {
        return profiles.apply(pid, in.profileId(), Actor.human);
    }

    @GetMapping("/projects/{pid}/templates")
    public List<TemplateService.AppliedTemplate> applied(@PathVariable String pid) {
        return templates.applied(pid);
    }

    @PostMapping("/projects/{pid}/templates/apply")
    public ChangesetService.Proposal applyTemplate(@PathVariable String pid, @RequestBody TemplateInput in) {
        return templates.apply(pid, in.templateId(), in.unitType(), in.plannedUnits(), in.scopeUnitId(), Actor.human);
    }

    @GetMapping("/projects/{pid}/coverage")
    public List<TemplateService.Coverage> coverage(@PathVariable String pid) {
        return templates.coverageAll(pid);
    }

    @GetMapping("/projects/{pid}/templates/{tid}/coverage")
    public TemplateService.Coverage coverageOf(@PathVariable String pid, @PathVariable String tid) {
        return templates.coverage(pid, tid);
    }

    @GetMapping("/projects/{pid}/threads")
    public List<NarrativeService.Thread> threads(@PathVariable String pid, @RequestParam(defaultValue = "false") boolean includeClosed) {
        return narrative.openThreads(pid, includeClosed);
    }

    @GetMapping("/projects/{pid}/writing-context/{unitId}")
    public NarrativeService.WritingContext writingContext(@PathVariable String pid, @PathVariable String unitId,
                                                          @RequestParam(required = false) Integer maxTokens,
                                                          @RequestParam(required = false) Integer tailParagraphs,
                                                          @RequestParam(required = false) List<String> focus) {
        return narrative.writingContext(pid, unitId, maxTokens, tailParagraphs, focus);
    }

    @GetMapping("/projects/{pid}/directives")
    public List<Views.Directive> directives(@PathVariable String pid) {
        return guidance.all(pid);
    }

    // ---------- units: text, cast, validation ----------

    @GetMapping("/projects/{pid}/units/{unitId}/text")
    public UnitService.TextRead getText(@PathVariable String pid, @PathVariable String unitId, @RequestParam(required = false) Integer version) {
        return units.getText(pid, unitId, version, null);
    }

    @PutMapping("/projects/{pid}/units/{unitId}/text")
    public UnitService.TextWrite setText(@PathVariable String pid, @PathVariable String unitId, @RequestBody TextInput in) {
        return units.setText(pid, unitId, in.text(), in.mode(), in.attribute(), in.separator(), in.note(), Actor.human, null);
    }

    @GetMapping("/projects/{pid}/units/{unitId}/text/history")
    public List<TextVersionRepository.Version> textHistory(@PathVariable String pid, @PathVariable String unitId) {
        return units.history(pid, unitId);
    }

    @GetMapping("/projects/{pid}/units/{unitId}/text/diff")
    public TextDiff.Result textDiff(@PathVariable String pid, @PathVariable String unitId, @RequestParam int from, @RequestParam(required = false) Integer to) {
        return units.diff(pid, unitId, from, to);
    }

    @PutMapping("/projects/{pid}/units/{unitId}/cast")
    public ChangesetService.Proposal setCast(@PathVariable String pid, @PathVariable String unitId, @RequestBody CastInput in) {
        return units.setCast(pid, unitId, in.nodeIds(), in.mode(), Actor.human, null);
    }

    @PostMapping("/projects/{pid}/units/{unitId}/validate")
    public UnitService.Validation validate(@PathVariable String pid, @PathVariable String unitId, @RequestBody(required = false) ValidateInput in) {
        return units.validate(pid, unitId, in == null ? null : in.text());
    }

    // ---------- export ----------

    @GetMapping("/projects/{pid}/export")
    public ResponseEntity<byte[]> exportManuscript(@PathVariable String pid, @RequestParam(defaultValue = "md") String format,
                                                   @RequestParam(required = false) String rootUnitId,
                                                   @RequestParam(required = false) List<String> unitTypes) {
        projects.get(pid);
        var file = export.export(pid, format, rootUnitId, unitTypes);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
