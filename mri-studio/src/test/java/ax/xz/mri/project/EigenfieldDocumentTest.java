package ax.xz.mri.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the simplified {@link EigenfieldDocument} — (id, name, description, script). */
class EigenfieldDocumentTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsBlankScript() {
        assertThrows(IllegalArgumentException.class,
            () -> new EigenfieldDocument(new ProjectNodeId("ef"), "B0", "", "   "));
        assertThrows(IllegalArgumentException.class,
            () -> new EigenfieldDocument(new ProjectNodeId("ef"), "B0", "", null));
    }

    @Test
    void withSettersReturnUpdatedCopies() {
        var ef = new EigenfieldDocument(new ProjectNodeId("ef"), "B0", "main", "return Vec3.of(0,0,1);");
        assertEquals("New Name", ef.withName("New Name").name());
        assertEquals("new description", ef.withDescription("new description").description());
        assertEquals("return Vec3.ZERO;", ef.withScript("return Vec3.ZERO;").script());
    }

    @Test
    void kindIsEigenfield() {
        var ef = new EigenfieldDocument(new ProjectNodeId("ef"), "B0", "", "return Vec3.of(0,0,1);");
        assertEquals(ProjectNodeKind.EIGENFIELD, ef.kind());
    }

    @Test
    void jsonRoundTrip() throws Exception {
        var original = new EigenfieldDocument(
            new ProjectNodeId("ef-1"), "B0", "Main field",
            "return Vec3.of(0, 0, 1);");
        String json = mapper.writeValueAsString(original);
        var loaded = mapper.readValue(json, EigenfieldDocument.class);
        assertEquals(original, loaded);
    }

    @Test
    void jsonDoesNotContainDerivedAccessors() throws Exception {
        var ef = new EigenfieldDocument(new ProjectNodeId("ef"), "B0", "", "return Vec3.of(0,0,1);");
        String json = mapper.writeValueAsString(ef);
        assertFalse(json.contains("\"kind\""), "kind is derived — must not be serialised: " + json);
    }
}
