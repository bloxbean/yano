package com.bloxbean.cardano.yano.app;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoMainTest {
    @Test
    void normalArgumentsAreNotConsumedAsMaintenance() {
        assertEquals(-1, YanoMain.runOfflineCommand(
                new String[]{"start:preprod"}, System.out, System.err));
    }

    @Test
    void repairRequiresExplicitConfirmationBeforeOpeningDatabase() {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int result = YanoMain.runOfflineCommand(
                new String[]{"repair", "pointer-index", "--database", "chainstate"},
                System.out, new PrintStream(errors));

        assertEquals(2, result);
        assertTrue(errors.toString().contains(
                "required: --confirm " + YanoMain.REPAIR_CONFIRMATION));
    }
}
