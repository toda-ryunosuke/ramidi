package com.rtoda3.ramidi;

import com.rtoda3.ramidi.facade.AudioGenerationPipeline;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

@SpringBootApplication
@ImportRuntimeHints(RamidiRuntimeHints.class)
@Slf4j
public class RamidiApplication {

    static void main(String[] args) {
        SpringApplication.run(RamidiApplication.class, args);
    }

    @Bean
    public CommandLineRunner run(AudioGenerationPipeline generationPipeline) {
        return args -> {
            var argInput = (args.length > 0)
                ? args[0]
                : "sample/main.ramidi"; // 指定ファイルがなければデフォルトのサンプルを読み込む

            var inputPath = Path.of(argInput);

            if (!inputPath.toFile().exists()) {
                log.error("Input file does not exist: {}", inputPath);
                return;
            }

            // パイプラインを実行
            var generated = generationPipeline.generate(inputPath);

            // 成果物の保存
            var basePath = removeExtension(inputPath); // 拡張子を除いたパス
            Files.write(addExtention(basePath, ".mid"), generated.midi());
            Files.write(addExtention(basePath, ".raw.wav"), generated.rawWav());
            Files.write(addExtention(basePath, ".wav"), generated.masteredWav());

            // 最終成果物を再生
            try {
                openFileInDefaultPlayer(Path.of(basePath + ".wav").toFile());
            } catch (Exception e) {
                log.error("Failed to open the file with the default player.", e);
            }

        };
    }

    private Path removeExtension(Path path) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');

        String fileNameWithoutExt = (dotIndex == -1) ? fileName : fileName.substring(0, dotIndex);

        Path parent = path.getParent();
        if (parent == null) {
            return Paths.get(fileNameWithoutExt);
        } else {
            return parent.resolve(fileNameWithoutExt);
        }
    }

    private Path addExtention(Path basePath, String ext) {
        return basePath.resolveSibling(basePath.getFileName() + ext);
    }

    private void openFileInDefaultPlayer(File file) throws IOException {
        var os = System.getProperty("os.name").toLowerCase();
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("cmd", "/c", "start", file.getAbsolutePath());
        } else if (os.contains("mac")) {
            pb = new ProcessBuilder("open", file.getAbsolutePath());
        } else { // Linux, Unix
            pb = new ProcessBuilder("xdg-open", file.getAbsolutePath());
        }
        pb.start();
        log.info("Attempted to open {} using OS-specific command.", file.getName());
    }
}