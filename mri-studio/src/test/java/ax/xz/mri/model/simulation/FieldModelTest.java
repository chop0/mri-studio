package ax.xz.mri.model.simulation;

import ax.xz.mri.project.EigenfieldDocument;
import ax.xz.mri.project.ProjectNodeId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the field model: {@link DrivePath}, {@link TransmitCoil}, {@link SimulationConfig}, {@link EigenfieldDocument}. */
class FieldModelTest {

    private static final ProjectNodeId EF_B0 = new ProjectNodeId("ef-b0");
    private static final ProjectNodeId EF_GX = new ProjectNodeId("ef-gx");
    private static final ProjectNodeId EF_RF = new ProjectNodeId("ef-rf");

    private static final double DT = 1e-6;

    private static DrivePath realPath(String name, String coilName, double minA, double maxA) {
        return new DrivePath(name, coilName, AmplitudeKind.REAL, 0, minA, maxA, null);
    }

    private static DrivePath quadraturePath(String name, String coilName, double carrierHz, double minA, double maxA) {
        return new DrivePath(name, coilName, AmplitudeKind.QUADRATURE, carrierHz, minA, maxA, null);
    }

    private static DrivePath staticPath(String name, String coilName, double maxA) {
        return new DrivePath(name, coilName, AmplitudeKind.STATIC, 0, 0, maxA, null);
    }

    private static SimulationConfig cfg(List<TransmitCoil> coils, List<DrivePath> paths) {
        return new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, DT,
            coils, paths, List.of());
    }

    @Test
    void amplitudeKindChannelCounts() {
        assertEquals(0, AmplitudeKind.STATIC.channelCount());
        assertEquals(1, AmplitudeKind.REAL.channelCount());
        assertEquals(2, AmplitudeKind.QUADRATURE.channelCount());
        assertEquals(1, AmplitudeKind.GATE.channelCount());
    }

    @Test
    void drivePathWithMethodsProduceNewInstances() {
        var original = staticPath("B0", "B0 Coil", 3.0);
        assertEquals("Main", original.withName("Main").name());
        assertEquals(AmplitudeKind.REAL, original.withKind(AmplitudeKind.REAL).kind());
        assertEquals(1.5, original.withMaxAmplitude(1.5).maxAmplitude());
        assertEquals(42e6, original.withCarrierHz(42e6).carrierHz());
        assertEquals("New Coil", original.withTransmitCoilName("New Coil").transmitCoilName());
        assertEquals("B0", original.name());
    }

    @Test
    void pathChannelCountFollowsAmplitudeKind() {
        assertEquals(0, staticPath("B0", "B0 Coil", 3.0).channelCount());
        assertEquals(1, realPath("Gx", "Gx Coil", -1, 1).channelCount());
        assertEquals(2, quadraturePath("RF", "RF Coil", 1e6, 0, 200e-6).channelCount());
    }

    @Test
    void totalChannelCountSumsOverDrivePaths() {
        var coils = List.of(
            new TransmitCoil("B0 Coil", EF_B0, 0),
            new TransmitCoil("Gx Coil", EF_GX, 0),
            new TransmitCoil("RF Coil", EF_RF, 0)
        );
        var paths = List.of(
            staticPath("B0", "B0 Coil", 1.5),
            realPath("Gx", "Gx Coil", -1, 1),
            quadraturePath("RF", "RF Coil", 1e6, 0, 200e-6)
        );
        var config = cfg(coils, paths);
        assertEquals(3, config.totalChannelCount());
    }

    @Test
    void omegaSimIsGammaTimesReferenceB0() {
        var config = cfg(List.of(), List.of());
        assertEquals(267.522e6 * 1.5, config.omegaSim(), 1e-3);
    }

    @Test
    void withReferenceB0TeslaReturnsUpdatedCopy() {
        var config = cfg(List.of(), List.of());
        assertEquals(3.0, config.withReferenceB0Tesla(3.0).referenceB0Tesla());
        assertEquals(1.5, config.referenceB0Tesla());
    }

    @Test
    void withDrivePathsProducesImmutableCopy() {
        var coils = List.of(new TransmitCoil("B0 Coil", EF_B0, 0));
        var paths = new java.util.ArrayList<DrivePath>(List.of(staticPath("B0", "B0 Coil", 3.0)));
        var config = cfg(coils, paths);
        paths.add(realPath("Extra", "B0 Coil", -1, 1));
        assertEquals(1, config.drivePaths().size());
    }

    @Test
    void eigenfieldDocumentKindIsEigenfield() {
        var ef = new EigenfieldDocument(EF_B0, "B0", "desc", "return Vec3.of(0,0,1);", "T", 1.0);
        assertEquals(ax.xz.mri.project.ProjectNodeKind.EIGENFIELD, ef.kind());
    }

    @Test
    void eigenfieldWithMethodsProduceNewInstances() {
        var ef = new EigenfieldDocument(EF_B0, "B0", "desc", "return Vec3.of(0,0,1);", "T", 1.0);
        assertEquals("Main", ef.withName("Main").name());
        assertEquals("new", ef.withDescription("new").description());
        assertEquals("return Vec3.ZERO;", ef.withScript("return Vec3.ZERO;").script());
        assertEquals("B0", ef.name());
    }

    @Test
    void zeroOrNegativeDtRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, 0.0, List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, -1e-6, List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            new SimulationConfig(1000, 100, 267.522e6, 5, 20, 30, 50, 5, 1.5, Double.NaN, List.of(), List.of(), List.of()));
    }

    @Test
    void withDtSecondsCopies() {
        var config = cfg(List.of(), List.of());
        var updated = config.withDtSeconds(5e-7);
        assertEquals(5e-7, updated.dtSeconds(), 0);
        assertEquals(DT, config.dtSeconds(), 0);
        assertEquals(1000, updated.t1Ms(), 0);
        assertEquals(1.5, updated.referenceB0Tesla(), 0);
    }

    @Test
    void larmorAndNyquistDerivations() {
        var config = cfg(List.of(), List.of());
        assertEquals(267.522e6 * 1.5 / (2 * Math.PI), config.larmorHz(), 1e-3);
        assertEquals(500_000, config.nyquistHz(), 1e-6);
    }

    @Test
    void jsonRoundtripPreservesDt() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var original = cfg(List.of(), List.of()).withDtSeconds(2.5e-6);
        String json = mapper.writeValueAsString(original);
        var parsed = mapper.readValue(json, SimulationConfig.class);
        assertEquals(2.5e-6, parsed.dtSeconds(), 0);
        assertEquals(1.5, parsed.referenceB0Tesla(), 0);
    }

    @Test
    void drivePathMustReferenceExistingTransmitCoilUnlessGate() {
        var coils = List.of(new TransmitCoil("Real", EF_B0, 0));
        var badPaths = List.of(realPath("Bogus", "DoesNotExist", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> cfg(coils, badPaths));
    }

    @Test
    void gatePathDoesNotRequireTransmitCoil() {
        var paths = List.of(new DrivePath("Gate", null, AmplitudeKind.GATE, 0, 0, 1, null));
        var config = cfg(List.of(), paths);
        assertEquals(1, config.drivePaths().size());
        assertTrue(config.drivePaths().getFirst().isGate());
    }
}
