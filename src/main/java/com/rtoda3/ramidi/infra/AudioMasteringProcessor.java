package com.rtoda3.ramidi.infra;

import com.rtoda3.ramidi.core.RamidiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AudioMasteringProcessor {

    /**
     * RAW WAVデータを受け取り、マスタリング済みWAVを返す
     */
    public byte[] masterWav(byte[] rawWavData, String masteringConfig) {
        // フィルタ設定が空の場合はマスタリングをスキップ
        if (masteringConfig == null || masteringConfig.isEmpty()) {
            log.info("Mastering skipped (Type: NONE). Returning raw WAV.");
            return rawWavData;
        }

        try {
            Path tempRawWav = null;
            Path tempMasterWav = null;

            try {
                tempRawWav = Files.createTempFile("raw-", ".wav");
                tempMasterWav = Files.createTempFile("master-", ".wav");

                Files.write(tempRawWav, rawWavData);

                CommandRunner.run(
                    "ffmpeg",
                    "-i", tempRawWav.toAbsolutePath().toString(),
                    "-filter:a", masteringConfig,
                    "-y",
                    tempMasterWav.toAbsolutePath().toString()
                );

                if (Files.size(tempMasterWav) == 0) {
                    throw new IOException("Mastered WAV generation failed: Output file is empty.");
                }

                log.info("Successfully mastered WAV: {} bytes", Files.size(tempMasterWav));
                return Files.readAllBytes(tempMasterWav);

            } finally {
                if (tempRawWav != null) {
                    Files.deleteIfExists(tempRawWav);
                }
                if (tempMasterWav != null) {
                    Files.deleteIfExists(tempMasterWav);
                }
            }

        } catch (IOException | InterruptedException e) {
            throw new RamidiException("Mastering failed", e);
        }

    }
}