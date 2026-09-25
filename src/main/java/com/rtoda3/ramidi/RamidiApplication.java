package com.rtoda3.ramidi;

import com.rtoda3.ramidi.facade.AudioGenerationPipeline;
import com.rtoda3.ramidi.facade.AudioGenerationPipeline.PipelineCommand;
import com.rtoda3.ramidi.facade.AudioGenerationPipeline.TargetStage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;

@SpringBootApplication
@ImportRuntimeHints(RamidiRuntimeHints.class)
@Slf4j
public class RamidiApplication {

    private static final String USAGE = """
        =========================================================
        Ramidi - MIDI & Audio Generation Pipeline
        =========================================================
        Usage: ramidi <input.ramidi> [options]

        Options:
          --stage=<stage>     処理を終了するステージを指定します。
                              [ midi | raw | wav | mp4 ] (デフォルト: mp4)
          --cover=<path>      MP4生成時の背景画像を指定します。
                              (デフォルト: <input>と同階層の cover.png)
          --no-play           処理完了後の自動再生を無効にします。
        =========================================================
        """;

    static void main(String[] args) {
        SpringApplication.run(RamidiApplication.class, args);
    }

    @Bean
    public ApplicationRunner run(AudioGenerationPipeline generationPipeline) {
        return args -> {
            // オプション(--xxx)以外の純粋な引数を取得
            var nonOptions = args.getNonOptionArgs();

            // ファイル未指定時は Usage を出して終了
            if (nonOptions.isEmpty()) {
                System.out.println(USAGE); // logはタイムスタンプなどが出てしまうので使わない
                return;
            }

            var inputPath = Path.of(nonOptions.getFirst());
            if (!inputPath.toFile().exists()) {
                log.error("Input file does not exist: {}", inputPath);
                return;
            }

            // オプション(--stage)を取得
            var stage = TargetStage.MP4; // デフォルトは最後まで
            if (args.containsOption("stage")) {
                var stageValues = args.getOptionValues("stage");
                if (stageValues != null && !stageValues.isEmpty()) {
                    stage = TargetStage.fromString(stageValues.getFirst());
                }
            }

            // オプション(--cover)を取得
            var coverJpg = inputPath.resolveSibling("cover.jpg");
            var coverPng = inputPath.resolveSibling("cover.png");
            var coverPath = Files.exists(coverJpg) ? coverJpg : coverPng;
            if (args.containsOption("cover")) {
                var coverValues = args.getOptionValues("cover");
                if (coverValues != null && !coverValues.isEmpty()) {
                    coverPath = Path.of(coverValues.getFirst());
                }
            }

            // パイプラインを実行
            var command = new PipelineCommand(inputPath, coverPath, stage);
            var generated = generationPipeline.generate(command);

            // 成果物の保存
            var basePathStr = removeExtension(inputPath).toString();
            var midiPath = Path.of(basePathStr + ".mid");
            var rawWavPath = Path.of(basePathStr + ".raw.wav");
            var wavPath = Path.of(basePathStr + ".wav");
            var mp4Path = Path.of(basePathStr + ".mp4");
            generated.midi().ifPresent(data -> saveFile(midiPath, data));
            generated.raw().ifPresent(data -> saveFile(rawWavPath, data));
            generated.wav().ifPresent(data -> saveFile(wavPath, data));
            generated.mp4().ifPresent(data -> saveFile(mp4Path, data));

            // 最終成果物を再生 ( --no-play オプションがなければ再生 )
            if (!args.containsOption("no-play")) {
                var targetFile = switch (stage) {
                    case MIDI -> midiPath.toFile();
                    case RAW -> rawWavPath.toFile();
                    case WAV -> wavPath.toFile();
                    case MP4 -> mp4Path.toFile();
                };

                if (targetFile.exists()) {
                    openFileInDefaultPlayer(targetFile);
                }
            }
        };
    }

    private void saveFile(Path path, byte[] data) {
        try {
            Files.write(path, data);
        } catch (IOException e) {
            log.error("Failed to write file: {}", path, e);
        }
    }

    private Path removeExtension(Path path) {
        var fileName = path.getFileName().toString();
        var dotIndex = fileName.lastIndexOf('.');

        var fileNameWithoutExt = (dotIndex == -1) ? fileName : fileName.substring(0, dotIndex);

        var parent = path.getParent();
        if (parent == null) {
            return Paths.get(fileNameWithoutExt);
        } else {
            return parent.resolve(fileNameWithoutExt);
        }
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