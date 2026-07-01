package com.redhat.migrationtoolkit.rhcl.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {

    private Messages messages;

    @BeforeEach
    void setUp() {
        messages = new Messages();
    }

    @Test
    void get_existingKey_returnsMessage() {
        String msg = messages.get("apply.success");
        assertNotNull(msg);
        assertFalse(msg.isBlank());
        assertNotEquals("apply.success", msg);
    }

    @Test
    void get_applyErrorNoFiles_returnsJapaneseMessage() {
        String msg = messages.get("apply.error.noFiles");
        assertNotNull(msg);
        assertNotEquals("apply.error.noFiles", msg);
    }

    @Test
    void get_importErrorNoFile_returnsMessage() {
        String msg = messages.get("import.error.noFile");
        assertNotNull(msg);
        assertNotEquals("import.error.noFile", msg);
    }

    @Test
    void get_importErrorNoYaml_returnsMessage() {
        String msg = messages.get("import.error.noYaml");
        assertNotNull(msg);
        assertNotEquals("import.error.noYaml", msg);
    }

    @Test
    void get_nonExistentKey_returnsKeyItself() {
        String key = "does.not.exist.abc123";
        String msg = messages.get(key);
        assertEquals(key, msg);
    }

    @Test
    void get_withFormatArgs_replacesPlaceholder() {
        String msg = messages.get("import.error.parseZip", "connection refused");
        assertNotNull(msg);
        assertNotEquals("import.error.parseZip", msg);
        assertTrue(msg.contains("connection refused"));
    }

    @Test
    void get_withMultipleArgs_formatsCorrectly() {
        String msg = messages.get("import.error.parseZip", "timeout", "extra");
        assertNotNull(msg);
        assertTrue(msg.contains("timeout"));
    }

    @Test
    void get_nullArgs_doesNotThrow() {
        assertDoesNotThrow(() -> messages.get("apply.success"));
    }

    @Test
    void get_emptyArgs_returnsPattern() {
        String msg = messages.get("apply.success");
        assertNotNull(msg);
    }
}
