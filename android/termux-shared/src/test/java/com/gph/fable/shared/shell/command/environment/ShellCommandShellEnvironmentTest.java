package com.gph.fable.shared.shell.command.environment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.gph.fable.shared.shell.command.ExecutionCommand;

import org.junit.Test;

import java.util.HashMap;

public class ShellCommandShellEnvironmentTest {

    private static final String RUNNER_NAME = ShellCommandShellEnvironment.ENV_SHELL_CMD__RUNNER_NAME;
    private static final String PACKAGE_NAME = ShellCommandShellEnvironment.ENV_SHELL_CMD__PACKAGE_NAME;
    private static final String SHELL_ID = ShellCommandShellEnvironment.ENV_SHELL_CMD__SHELL_ID;
    private static final String SHELL_NAME = ShellCommandShellEnvironment.ENV_SHELL_CMD__SHELL_NAME;

    private HashMap<String, String> environmentFor(String packageName, ExecutionCommand executionCommand) {
        return new ShellCommandShellEnvironment().getEnvironmentForPackageName(packageName, executionCommand);
    }

    /** 回归：RUNNER_NAME 必须是枚举契约值（app-shell），不是 Kotlin 枚举内建名（APP_SHELL）。 */
    @Test
    public void exportsRunnerContractName() {
        ExecutionCommand appShell = new ExecutionCommand();
        appShell.runner = "app-shell";
        assertEquals("app-shell", environmentFor("com.gph.fable", appShell).get(RUNNER_NAME));

        ExecutionCommand terminalSession = new ExecutionCommand();
        terminalSession.runner = "terminal-session";
        assertEquals("terminal-session", environmentFor("com.gph.fable", terminalSession).get(RUNNER_NAME));
    }

    @Test
    public void exportsPackageNameShellIdAndShellName() {
        ExecutionCommand executionCommand = new ExecutionCommand();
        executionCommand.runner = "app-shell";
        executionCommand.id = 42;
        executionCommand.shellName = "fable";

        HashMap<String, String> environment = environmentFor("com.gph.fable", executionCommand);
        assertEquals("com.gph.fable", environment.get(PACKAGE_NAME));
        assertEquals("42", environment.get(SHELL_ID));
        assertEquals("fable", environment.get(SHELL_NAME));
    }

    /** 与 Java 原件 String.valueOf(id) 等价：id 为 null 时导出字面量 "null"，而不是不导出。 */
    @Test
    public void exportsLiteralNullShellIdWhenIdUnset() {
        ExecutionCommand executionCommand = new ExecutionCommand();
        executionCommand.runner = "app-shell";
        assertNull(executionCommand.id);
        assertEquals("null", environmentFor("com.gph.fable", executionCommand).get(SHELL_ID));
    }

    @Test
    public void skipsUnsetShellName() {
        ExecutionCommand executionCommand = new ExecutionCommand();
        executionCommand.runner = "app-shell";
        HashMap<String, String> environment = environmentFor("com.gph.fable", executionCommand);
        assertTrue(environment.containsKey(RUNNER_NAME));
        assertTrue(!environment.containsKey(SHELL_NAME));
    }

    @Test
    public void returnsEmptyEnvironmentForUnknownRunner() {
        ExecutionCommand executionCommand = new ExecutionCommand();
        executionCommand.runner = "unknown-runner";
        executionCommand.id = 7;
        assertTrue(environmentFor("com.gph.fable", executionCommand).isEmpty());
    }
}
