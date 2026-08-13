package com.gph.fable.shared.shell.command.environment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;

public class ShellEnvironmentUtilsTest {
    @Test
    public void validatesNamesAndValuesAtTheEnvironmentSeam() {
        assertTrue(ShellEnvironmentUtils.isValidEnvironmentVariableName("FABLE_HOME"));
        assertTrue(ShellEnvironmentUtils.isValidEnvironmentVariableValue("/data/data"));
        assertFalse(ShellEnvironmentUtils.isValidEnvironmentVariableName("9FABLE"));
        assertFalse(ShellEnvironmentUtils.isValidEnvironmentVariableValue("bad\0value"));
    }

    @Test
    public void convertsEnvironmentToStableDotEnvLines() {
        HashMap<String, String> environment = new HashMap<>();
        environment.put("Z_LAST", "tail");
        environment.put("A_FIRST", "quote\" dollar$");
        environment.put("INVALID-NAME", "ignored");

        assertEquals(
            "export A_FIRST=\"quote\\\" dollar\\$\"\nexport Z_LAST=\"tail\"\n",
            ShellEnvironmentUtils.convertEnvironmentToDotEnvFile(environment)
        );
    }
}
