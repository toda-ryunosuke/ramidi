package com.rtoda3.ramidi.infra;

import com.rtoda3.ramidi.core.RamidiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AudioToVideoProcessor {

    /**
     * ジャケット画像とマスタリング済みWAV(byte配列)から、X(Twitter)投稿用のMP4動画を生成する
     */
    public byte[] generateMp4(Path coverPath, byte[] audioData) {

        try {

            Path tempWav = null;
            Path tempMp4 = null;

            try {
                tempWav = Files.createTempFile("audio-", ".wav");
                tempMp4 = Files.createTempFile("video-", ".mp4");

                Files.write(tempWav, audioData);

                // FFmpegのコマンドを動的に組み立てる
                var command = new ArrayList<String>();
                command.add("ffmpeg");

                if (coverPath != null && Files.exists(coverPath)) {
                    // 画像あり: 1280x720の背景の下部に、小さめのLR分割波形(高さ240)を重ねる
                    command.addAll(List.of(
                        "-loop", "1",
                        "-framerate", "30",
                        "-i", coverPath.toAbsolutePath().toString(),
                        "-i", tempWav.toAbsolutePath().toString(),
                        "-filter_complex",
                        "[0:v]scale=1280:720:force_original_aspect_ratio=increase,crop=1280:720[bg];"
                            +
                            "[1:a]showwaves=s=1280x240:mode=cline:rate=30:colors=cyan|magenta:split_channels=1,colorkey=black:0.1:0.1[wave];"
                            + // split_channels=1 でマルチチャンネル化
                            "[bg][wave]overlay=0:H-h-40:format=auto:shortest=1[v]", // H-h-40 で下から40pxの位置に配置
                        "-map", "[v]",
                        "-map", "1:a"
                    ));
                    log.info("カバー画像の下部にマルチチャンネル波形を合成してMP4を生成します。");
                } else {
                    // 画像なし: 黒背景(1280x720)の中央にマルチチャンネル波形を配置
                    command.addAll(List.of(
                        "-f", "lavfi",
                        "-i", "color=c=black:s=1280x720:r=30", // ベースの黒背景
                        "-i", tempWav.toAbsolutePath().toString(),
                        "-filter_complex",
                        "[1:a]showwaves=s=1280x240:mode=cline:rate=30:colors=cyan|magenta:split_channels=1,colorkey=black:0.1:0.1[wave];"
                            +
                            "[0:v][wave]overlay=0:(H-h)/2:shortest=1[v]", // 中央に配置
                        "-map", "[v]",
                        "-map", "1:a"
                    ));
                    log.info(
                        "カバー画像が見つからないため、黒背景のマルチチャンネル波形MP4を生成します。");
                }

                // 共通のエンコードオプション（X対応の高音質・30fps設定）
                command.addAll(List.of(
                    "-c:v", "libx264",
                    "-c:a", "aac",
                    "-b:a", "320k",
                    "-ar", "44100",
                    "-pix_fmt", "yuv420p",
                    "-shortest",
                    "-y",
                    tempMp4.toAbsolutePath().toString()
                ));

                CommandRunner.run(command.toArray(new String[0]));

                if (Files.size(tempMp4) == 0) {
                    throw new IOException("MP4 generation failed: Output file is empty.");
                }

                log.info("X投稿用 MP4 の生成に成功しました");
                return Files.readAllBytes(tempMp4);

            } finally {
                if (tempWav != null) {
                    Files.deleteIfExists(tempWav);
                }
                if (tempMp4 != null) {
                    Files.deleteIfExists(tempMp4);
                }
            }
        } catch (IOException | InterruptedException e) {
            throw new RamidiException("MP4 の生成に失敗しました", e);
        }

    }
}
