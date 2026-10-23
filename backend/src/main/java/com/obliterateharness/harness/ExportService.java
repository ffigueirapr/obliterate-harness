package com.obliterateharness.harness;

import com.obliterateharness.domain.DomainException;
import com.obliterateharness.domain.Manuscript;
import com.obliterateharness.domain.NodeTypeDef;
import com.obliterateharness.domain.Views;
import com.obliterateharness.domain.Views.Detail;
import com.obliterateharness.graph.Graph;
import com.obliterateharness.graph.GraphReader;
import com.obliterateharness.graph.SchemaStore;
import com.obliterateharness.service.ProjectService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.obliterateharness.i18n.I18n.t;

/**
 * Compiles a project's manuscript (units in narrative order) into md, txt, html, docx or epub.
 * docx and epub are written by hand (they are zip files with a few XML parts), so no extra library is needed.
 */
@Service
public class ExportService {

    public record Section(int depth, String title, String text, long words) {}

    public record Manuscript_(String title, List<Section> sections, long words) {}

    public record ExportFile(String filename, String contentType, byte[] content) {}

    private final Graph graph;
    private final GraphReader reader;
    private final SchemaStore schema;
    private final ProjectService projects;

    public ExportService(Graph graph, GraphReader reader, SchemaStore schema, ProjectService projects) {
        this.graph = graph;
        this.reader = reader;
        this.schema = schema;
        this.projects = projects;
    }

    /**
     * @param rootUnitId only this unit and its descendants (e.g. one volume); null = the whole work
     * @param unitTypes  only units of these types become sections (e.g. [Chapter]); null = every unit with text, plus headings
     */
    public Manuscript_ compile(String projectId, String rootUnitId, List<String> unitTypes) {
        var project = projects.get(projectId);
        return graph.read(tx -> {
            var cache = schema.cache(tx, projectId);
            List<String> ids;
            if (rootUnitId != null && !rootUnitId.isBlank()) {
                var root = reader.node(tx, projectId, rootUnitId, Detail.summary);
                if (!"Unit".equals(root.base())) throw new DomainException(t("rootUnitId must be a narrative unit", "rootUnitId deve ser uma unidade narrativa"));
                ids = tx.run("""
                        MATCH (r:N {id:$r}) OPTIONAL MATCH (r)-[:R*1..10 {kind:'structure'}]->(u:N)
                        WITH r, collect(DISTINCT u) AS kids
                        UNWIND [r] + kids AS n WITH n WHERE n IS NOT NULL
                        RETURN n.id AS id ORDER BY n.sortKey
                        """, Map.of("r", rootUnitId)).list(r -> r.get("id").asString());
            } else {
                ids = tx.run("MATCH (u:N:Unit {projectId:$p}) RETURN u.id AS id ORDER BY u.sortKey", Map.of("p", projectId))
                        .list(r -> r.get("id").asString());
            }
            int rootDepth = rootUnitId == null ? 0 : -1;
            List<Section> sections = new ArrayList<>();
            long total = 0;
            for (var n : reader.nodes(tx, projectId, ids, Detail.full)) {
                if (unitTypes != null && !unitTypes.isEmpty() && unitTypes.stream().noneMatch(x -> x.equalsIgnoreCase(n.type()))) continue;
                NodeTypeDef type = cache.nodeType(n.type()).orElse(null);
                String text = type == null || n.attributes() == null ? null
                        : Manuscript.textAttr(type).map(a -> n.attributes().get(a.key())).map(Object::toString).orElse(null);
                int depth = n.sortKey() == null ? 1 : n.sortKey().split("\\.").length;
                if (rootDepth < 0) rootDepth = depth - 1;
                long words = Manuscript.countWords(text);
                total += words;
                sections.add(new Section(Math.max(1, depth - rootDepth), n.name(), text, words));
            }
            if (sections.stream().noneMatch(s -> s.text() != null && !s.text().isBlank())) {
                throw new DomainException(t("No manuscript text found in this selection", "Nenhum texto de manuscrito encontrado nessa seleção"));
            }
            return new Manuscript_(project.name(), sections, total);
        });
    }

    public ExportFile export(String projectId, String format, String rootUnitId, List<String> unitTypes) {
        var m = compile(projectId, rootUnitId, unitTypes);
        String base = safeName(m.title());
        return switch (format == null ? "md" : format.toLowerCase()) {
            case "md", "markdown" -> new ExportFile(base + ".md", "text/markdown; charset=utf-8", markdown(m).getBytes(StandardCharsets.UTF_8));
            case "txt", "text" -> new ExportFile(base + ".txt", "text/plain; charset=utf-8", plain(m).getBytes(StandardCharsets.UTF_8));
            case "html" -> new ExportFile(base + ".html", "text/html; charset=utf-8", html(m).getBytes(StandardCharsets.UTF_8));
            case "docx" -> new ExportFile(base + ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx(m));
            case "epub" -> new ExportFile(base + ".epub", "application/epub+zip", epub(m, projects.locale(projectId).name()));
            default -> throw new DomainException(t("Unknown format '", "Formato desconhecido '") + format + "'. md, txt, html, docx, epub");
        };
    }

    // ------------------------------------------------------------------ text formats

    static String markdown(Manuscript_ m) {
        StringBuilder sb = new StringBuilder("# ").append(m.title()).append("\n\n");
        for (Section s : m.sections()) {
            sb.append("#".repeat(Math.min(6, s.depth() + 1))).append(' ').append(s.title()).append("\n\n");
            if (s.text() != null && !s.text().isBlank()) sb.append(s.text().strip()).append("\n\n");
        }
        return sb.toString();
    }

    static String plain(Manuscript_ m) {
        StringBuilder sb = new StringBuilder(m.title().toUpperCase()).append("\n\n");
        for (Section s : m.sections()) {
            sb.append(s.title()).append("\n").append("-".repeat(Math.min(60, s.title().length()))).append("\n\n");
            if (s.text() != null && !s.text().isBlank()) sb.append(s.text().strip()).append("\n\n");
        }
        return sb.toString();
    }

    static String html(Manuscript_ m) {
        StringBuilder sb = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>").append(esc(m.title()))
                .append("</title><style>body{max-width:42em;margin:2em auto;font:1.1em/1.6 Georgia,serif;padding:0 1em}h1,h2,h3{font-family:sans-serif}</style></head><body>")
                .append("<h1>").append(esc(m.title())).append("</h1>");
        for (Section s : m.sections()) {
            int h = Math.min(6, s.depth() + 1);
            sb.append("<h").append(h).append('>').append(esc(s.title())).append("</h").append(h).append('>');
            paragraphs(s.text()).forEach(p -> sb.append("<p>").append(esc(p)).append("</p>"));
        }
        return sb.append("</body></html>").toString();
    }

    // ------------------------------------------------------------------ docx (WordprocessingML, minimal)

    static byte[] docx(Manuscript_ m) {
        StringBuilder body = new StringBuilder();
        body.append(docxPara(m.title(), "Title"));
        for (Section s : m.sections()) {
            body.append(docxPara(s.title(), "Heading" + Math.min(6, s.depth())));
            paragraphs(s.text()).forEach(p -> body.append(docxPara(p, null)));
        }
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body
                + "<w:sectPr/></w:body></w:document>";
        StringBuilder styles = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"160\" w:line=\"300\" w:lineRule=\"auto\"/></w:pPr>"
                + "<w:rPr><w:sz w:val=\"24\"/></w:rPr></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:rPr><w:b/><w:sz w:val=\"48\"/></w:rPr></w:style>");
        for (int i = 1; i <= 6; i++) {
            styles.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading").append(i).append("\"><w:name w:val=\"heading ").append(i)
                    .append("\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/><w:spacing w:before=\"240\"/><w:outlineLvl w:val=\"").append(i - 1)
                    .append("\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"").append(Math.max(24, 40 - i * 4)).append("\"/></w:rPr></w:style>");
        }
        styles.append("</w:styles>");
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "</Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";
        String docRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>";
        return zip(List.of(
                new Entry("[Content_Types].xml", contentTypes, false),
                new Entry("_rels/.rels", rels, false),
                new Entry("word/_rels/document.xml.rels", docRels, false),
                new Entry("word/document.xml", document, false),
                new Entry("word/styles.xml", styles.toString(), false)));
    }

    private static String docxPara(String text, String style) {
        StringBuilder p = new StringBuilder("<w:p>");
        if (style != null) p.append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>");
        String[] lines = text.split("\\r?\\n");
        p.append("<w:r>");
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) p.append("<w:br/>");
            p.append("<w:t xml:space=\"preserve\">").append(esc(lines[i])).append("</w:t>");
        }
        return p.append("</w:r></w:p>").toString();
    }

    // ------------------------------------------------------------------ epub 3

    static byte[] epub(Manuscript_ m, String lang) {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("mimetype", "application/epub+zip", true));
        entries.add(new Entry("META-INF/container.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>"
                + "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>", false));
        // each top-level text section becomes a chapter file; headings without text go into the next file
        List<String> files = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (Section s : m.sections()) {
            int h = Math.min(6, s.depth() + 1);
            pending.append("<h").append(h).append('>').append(esc(s.title())).append("</h").append(h).append('>');
            if (s.text() == null || s.text().isBlank()) continue;
            paragraphs(s.text()).forEach(p -> pending.append("<p>").append(esc(p)).append("</p>"));
            String name = "c" + (files.size() + 1) + ".xhtml";
            entries.add(new Entry("OEBPS/" + name, xhtml(s.title(), pending.toString(), lang), false));
            files.add(name);
            titles.add(s.title());
            pending.setLength(0);
        }
        StringBuilder nav = new StringBuilder("<nav epub:type=\"toc\" id=\"toc\"><h1>").append(esc(m.title())).append("</h1><ol>");
        for (int i = 0; i < files.size(); i++) nav.append("<li><a href=\"").append(files.get(i)).append("\">").append(esc(titles.get(i))).append("</a></li>");
        nav.append("</ol></nav>");
        entries.add(new Entry("OEBPS/nav.xhtml", xhtml(m.title(), nav.toString(), lang), false));
        StringBuilder manifest = new StringBuilder("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>");
        StringBuilder spine = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            manifest.append("<item id=\"c").append(i + 1).append("\" href=\"").append(files.get(i)).append("\" media-type=\"application/xhtml+xml\"/>");
            spine.append("<itemref idref=\"c").append(i + 1).append("\"/>");
        }
        entries.add(new Entry("OEBPS/content.opf", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:identifier id=\"uid\">urn:uuid:" + UUID.randomUUID() + "</dc:identifier><dc:title>" + esc(m.title()) + "</dc:title>"
                + "<dc:language>" + lang + "</dc:language><meta property=\"dcterms:modified\">" + java.time.Instant.now().toString().substring(0, 19) + "Z</meta>"
                + "</metadata><manifest>" + manifest + "</manifest><spine>" + spine + "</spine></package>", false));
        return zip(entries);
    }

    private static String xhtml(String title, String body, String lang) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" lang=\""
                + lang + "\"><head><title>" + esc(title) + "</title></head><body>" + body + "</body></html>";
    }

    // ------------------------------------------------------------------ helpers

    private record Entry(String name, String content, boolean stored) {}

    private static byte[] zip(List<Entry> entries) {
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes)) {
            for (Entry e : entries) {
                byte[] data = e.content().getBytes(StandardCharsets.UTF_8);
                ZipEntry ze = new ZipEntry(e.name());
                if (e.stored()) {
                    // EPUB requires the mimetype entry first and uncompressed
                    ze.setMethod(ZipEntry.STORED);
                    ze.setSize(data.length);
                    ze.setCompressedSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    ze.setCrc(crc.getValue());
                }
                zip.putNextEntry(ze);
                zip.write(data);
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static List<String> paragraphs(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : text.strip().split("\\n\\s*\\n")) if (!p.isBlank()) out.add(p.strip());
        return out;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String safeName(String s) {
        String n = s.replaceAll("[\\\\/:*?\"<>|]+", "").trim();
        return n.isEmpty() ? "manuscript" : n;
    }

}
