package com.obliterateharness.api;

import com.obliterateharness.domain.Actor;
import com.obliterateharness.domain.DomainException;
import com.obliterateharness.harness.ArchiveService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.obliterateharness.i18n.I18n.t;

/** Backup and migration: export projects to an obliterate-archive JSON file and import it back (see ArchiveService). */
@RestController
@RequestMapping("/api")
public class ArchiveController {
    private final ArchiveService archives;

    public ArchiveController(ArchiveService archives) {
        this.archives = archives;
    }

    /** One project and its context. Without ?format (that one is the manuscript export). */
    @GetMapping(value = "/projects/{pid}/export", params = "!format")
    public ResponseEntity<ArchiveService.Archive> exportProject(@PathVariable String pid) {
        return download(archives.exportProject(pid));
    }

    /** Every project and every custom context. */
    @GetMapping("/export")
    public ResponseEntity<ArchiveService.Archive> exportAll() {
        return download(archives.exportAll());
    }

    /**
     * Imports an archive as new projects (multipart field "file").
     *
     * @param recreateContexts recreate custom contexts that do not exist here from the archive (default true)
     * @param name             new name for the project (single-project archives)
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ArchiveService.ImportResult importFile(@RequestParam("file") MultipartFile file, @RequestParam(required = false) Boolean recreateContexts,
                                                  @RequestParam(required = false) String name) {
        String text;
        try {
            text = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DomainException(t("Could not read the uploaded file: ", "Não foi possível ler o arquivo enviado: ") + e.getMessage());
        }
        return archives.importArchive(archives.parse(text), new ArchiveService.ImportOptions(recreateContexts, name), Actor.human);
    }

    /** Same as the multipart import, with the archive as the JSON body. */
    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ArchiveService.ImportResult importJson(@RequestBody String body, @RequestParam(required = false) Boolean recreateContexts,
                                                  @RequestParam(required = false) String name) {
        return archives.importArchive(archives.parse(body), new ArchiveService.ImportOptions(recreateContexts, name), Actor.human);
    }

    private static ResponseEntity<ArchiveService.Archive> download(ArchiveService.Archive a) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(ArchiveService.filename(a), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(a);
    }
}
