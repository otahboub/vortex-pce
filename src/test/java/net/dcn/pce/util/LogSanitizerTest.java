package net.dcn.pce.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogSanitizerTest {

    @Test
    void untrustedValuesCannotCreateAdditionalLogLines() {
        assertEquals("speaker:alpha__forged_entry",
                LogSanitizer.singleLine("speaker:alpha\r\nforged\u2028entry"));
    }

    @Test
    void nullHasAStableRepresentation() {
        assertEquals("null", LogSanitizer.singleLine(null));
    }
}
