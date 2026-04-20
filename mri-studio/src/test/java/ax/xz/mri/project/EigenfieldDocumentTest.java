package ax.xz.mri.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link EigenfieldDocument}. */
class EigenfieldDocumentTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsBlankScript() {
        assertThrows(IllegalArgumentException.class,
            () -> new EigenfieldDocument(new ProjectNodeId("ef"), "B0", "", "   ", "T"));
        assertThrows(IllegalArgumentException.class,
            () -> new EigenfieldDocument(new ProjectNodeId("ef"), "B0", "", null, "T"));
    }

    @Test
    void withSettersReturnUpdatedCopies() {
        var ef = new EigenfieldDocument(new ProjectNodeId("ef"), "B0", "main", "return Vec3.of(0,0,1);", "T");
        assertEquals("New Name", ef.withName("New Name").name());
        assertEquals("new description", ef.withDescription("new description").description());
        assertEquals("return Vec3.ZERO;", ef.withScript("return Vec3.ZERO;").script());
        assertEquals("T/m", ef.withUnits("T/m").units());
    }

    @Test
    void kindIsEigenfield() {
        var ef = new EigenfieldDocument(new ProjectNodeId("ef"), "B0", "", "return Vec3.of(0,0,1);", "T");
        assertEquals(ProjectNodeKind.EIGENFIELD, ef.kind());
    }

    @Test
    void jsonRoundTrip() throws Exception {
        var original = new EigenfieldDocument(
            new ProjectNodeId("ef-1"), "B0", "Main field",
            "return Vec3.of(0, 0, 1);", "T");
        String json = mapper.writeValueAsString(original);
        var loaded = mapper.readValue(json, EigenfieldDocument.class);
        assertEquals(original, loaded);
    }

    @Test
    void jsonDoesNotContainDerivedAccessors() throws Exception {
        var ef = new EigenfieldDocument(new ProjectNodeId("ef"), "B0", "", "return Vec3.of(0,0,1);", "T");
        String json = mapper.writeValueAsString(ef);
        assertFalse(json.contains("\"kind\""), "kind is derived — must not be serialised: " + json);
    }

    @Test
    void nullUnitsNormalisesToEmpty() {
        var ef = new EigenfieldDocument(new ProjectNodeId("ef"), "B0", "", "return Vec3.of(0,0,1);", null);
        assertEquals("", ef.units());
    }
}
