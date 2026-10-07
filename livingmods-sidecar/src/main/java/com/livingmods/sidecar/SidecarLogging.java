package com.livingmods.sidecar;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class SidecarLogging {
    private static volatile PrintWriter fileWriter;
    private static final Logger ROOT = Logger.getLogger("livingmods.sidecar");

    private SidecarLogging() {}

    public static void init(Path saveDir) throws IOException {
        Path logFile = saveDir.resolve("livingmods-sidecar.log");
        Files.createDirectories(saveDir);
        fileWriter = new PrintWriter(Files.newBufferedWriter(logFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        ROOT.setLevel(Level.INFO);
        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(new SidecarFormatter());
        ROOT.addHandler(console);
        ROOT.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(LogRecord record) {
                if (fileWriter != null && isLoggable(record)) {
                    fileWriter.println(new SidecarFormatter().format(record));
                    fileWriter.flush();
                }
            }

            @Override
            public void flush() {
                if (fileWriter != null) fileWriter.flush();
            }

            @Override
            public void close() {
                if (fileWriter != null) fileWriter.close();
            }
        });
    }

    public static Logger logger(Class<?> type) {
        return Logger.getLogger(type.getName());
    }

    private static final class SidecarFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return Instant.now() + " [" + record.getLevel() + "] " + record.getMessage() + "\n";
        }
    }
}
