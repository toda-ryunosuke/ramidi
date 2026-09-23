package com.rtoda3.ramidi.infra;

import java.io.IOException;
import lombok.Getter;

/**
 * An exception thrown when an external command fails to execute successfully.
 * It captures the exit code, standard output, and standard error for detailed diagnostics.
 */
@Getter
public class CommandException extends IOException {

    private final int exitCode;
    private final String stdout;
    private final String stderr;

    public CommandException(String message, int exitCode, String stdout, String stderr) {
        super(String.format("%s (Exit Code: %d)\n--- STDOUT ---\n%s\n--- STDERR ---\n%s",
              message, exitCode, stdout, stderr));
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
    }
}