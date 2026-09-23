package com.rtoda3.ramidi.infra;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A utility class for running external commands and capturing their output.
 */
public class CommandRunner {

    /**
     * Executes a command with a custom environment and waits for it to complete.
     *
     * @param env     A map of environment variables to set for the command.
     * @param command The command and its arguments to execute.
     * @return The standard output of the command.
     * @throws IOException          If an I/O error occurs.
     * @throws InterruptedException If the thread is interrupted while waiting for the command to finish.
     * @throws CommandException     If the command returns a non-zero exit code.
     */
    public static String run(Map<String, String> env, String... command) throws IOException, InterruptedException, CommandException {
        var pb = new ProcessBuilder(command);
        
        // Set environment variables
        if (env != null) {
            pb.environment().putAll(env);
        }

        var process = pb.start();

        // Capture stdout and stderr separately
        var stdout = new StringBuilder();
        var stderr = new StringBuilder();

        try (var stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
             var stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

            // Non-blocking read of both streams
            var stdoutThread = new Thread(() -> stdoutReader.lines().forEach(line -> stdout.append(line).append("\n")));
            var stderrThread = new Thread(() -> stderrReader.lines().forEach(line -> stderr.append(line).append("\n")));
            stdoutThread.start();
            stderrThread.start();

            // Wait for the process to complete
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Command timed out after 60 seconds.");
            }
            
            // Wait for reader threads to finish
            stdoutThread.join();
            stderrThread.join();
        }

        var exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new CommandException("Command failed", exitCode, stdout.toString(), stderr.toString());
        }

        return stdout.toString();
    }
    
    /**
     * Executes a command and waits for it to complete.
     *
     * @param command The command and its arguments to execute.
     * @return The standard output of the command.
     * @throws IOException          If an I/O error occurs.
     * @throws InterruptedException If the thread is interrupted while waiting for the command to finish.
     * @throws CommandException     If the command returns a non-zero exit code.
     */
    public static String run(String... command) throws IOException, InterruptedException, CommandException {
        return run(null, command);
    }
}