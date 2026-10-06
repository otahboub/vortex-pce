package net.dcn.pce.pcep;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes the Java encoder's frames where the Wireshark gate can find them.
 *
 * <p>A decoder written by the same author as an encoder will agree with it whether or not either
 * is correct. Wireshark's PCEP dissector was written from the RFCs with no sight of this code, so
 * running it over these bytes is the only check here that is not this project agreeing with
 * itself. The CI step reads this file; the assertions live in
 * {@code tests/interop/dissect_with_tshark.py}.
 */
class PcepEncoderFrameDumpTest {

    private static final Path OUTPUT = Path.of("target", "java-pcep-frames.hex");

    @Test
    void writesFramesForIndependentDissection() throws IOException {
        List<String> lines = List.of(
                "OPEN " + hex(PcepEncoder.open(30, 120, 1, "vortex-java")),
                "KEEPALIVE " + hex(PcepEncoder.keepalive()),
                "PCINITIATE " + hex(PcepEncoder.pcInitiate(
                        7L, "vortex-T1", "10.0.0.1", "10.0.0.2", 1.0e8,
                        List.of("10.0.0.1", "10.0.0.9", "10.0.0.2"))),
                // Distinct PLSP-ID and name so the dissection cannot confuse this frame with the
                // PCRpt fixture, which also carries PLSP-ID 42.
                "PCINITIATE_REMOVE " + hex(PcepEncoder.pcInitiateRemoval(
                        8L, 4242L, "vortex-REMOVE")));

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        assertTrue(Files.size(OUTPUT) > 0, "the dissection gate has nothing to check");
    }

    private static String hex(byte[] frame) {
        StringBuilder out = new StringBuilder(frame.length * 2);
        for (byte b : frame) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }
}
