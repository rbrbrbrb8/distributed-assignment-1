package com.distributedsystems.localapp;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The command-line arguments defined by the assignment:
 * <pre>
 * java -jar local-app.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]
 * </pre>
 *
 * @param inputFile       text file with one image URL per line (must exist)
 * @param outputFile      HTML file to create
 * @param managerClientsN maximum number of clients a single manager may serve
 * @param workerFilesN    "n": images per worker; the manager starts one worker per n images
 * @param terminate       whether to send a terminate message to the manager at the end
 */
public record LocalAppArgs(Path inputFile, Path outputFile, int managerClientsN, int workerFilesN, boolean terminate) {

    public static final String USAGE =
            "Usage: java -jar local-app.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]";

    /**
     * Parses and validates the arguments.
     *
     * @throws IllegalArgumentException with a readable message (the caller prints it together with {@link #USAGE})
     */
    public static LocalAppArgs parse(String[] args) {
        if (args == null || args.length < 4 || args.length > 5) {
            throw new IllegalArgumentException("Expected 4 or 5 arguments, got " + (args == null ? 0 : args.length));
        }

        Path inputFile = Path.of(args[0]);
        if (!Files.isRegularFile(inputFile)) {
            throw new IllegalArgumentException("Input file not found: " + inputFile.toAbsolutePath());
        }

        Path outputFile = Path.of(args[1]);
        Path outputDir = outputFile.toAbsolutePath().getParent();
        if (outputDir != null && !Files.isDirectory(outputDir)) {
            throw new IllegalArgumentException("Output directory does not exist: " + outputDir);
        }

        int managerClientsN = parsePositive("manager-clients-n", args[2]);
        int workerFilesN = parsePositive("worker-files-n", args[3]);

        boolean terminate = false;
        if (args.length == 5) {
            if (!args[4].equals("terminate")) {
                throw new IllegalArgumentException("Unknown 5th argument '" + args[4] + "' (only 'terminate' is allowed)");
            }
            terminate = true;
        }

        return new LocalAppArgs(inputFile, outputFile, managerClientsN, workerFilesN, terminate);
    }

    private static int parsePositive(String name, String value) {
        try {
            int n = Integer.parseInt(value.trim());
            if (n <= 0) {
                throw new IllegalArgumentException(name + " must be a positive integer, got " + n);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive integer, got '" + value + "'");
        }
    }
}
