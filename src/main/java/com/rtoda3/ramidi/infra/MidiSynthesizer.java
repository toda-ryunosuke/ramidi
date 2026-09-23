package com.rtoda3.ramidi.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
@Slf4j
public class MidiSynthesizer {

    /**
     * MIDIバイナリデータとサウンドフォントのパスを受け取り、RAW WAVを生成して返す
     */
    public byte[] renderMidiToRawWav(byte[] midiData, String soundFontPath) throws IOException, InterruptedException, CommandException {
        var sfFile = new File(soundFontPath);
        if (!sfFile.exists()) {
            throw new IOException("SoundFont file not found at: " + soundFontPath);
        }

        Path tempMid = null;
        Path tempRawWav = null;

        try {
            tempMid = Files.createTempFile("midi-", ".mid");
            tempRawWav = Files.createTempFile("raw-", ".wav");

            Files.write(tempMid, midiData);

            CommandRunner.run(
                "fluidsynth",
                "-ni",
                "-F", tempRawWav.toAbsolutePath().toString(),
                "-r", "44100",
                soundFontPath,
                tempMid.toAbsolutePath().toString()
            );

            if (Files.size(tempRawWav) == 0) {
                throw new IOException("Raw WAV rendering failed: Output file is empty.");
            }

            log.info("Successfully rendered raw WAV: {} bytes", Files.size(tempRawWav));
            return Files.readAllBytes(tempRawWav);

        } finally {
            if (tempMid != null) Files.deleteIfExists(tempMid);
            if (tempRawWav != null) Files.deleteIfExists(tempRawWav);
        }
    }
}