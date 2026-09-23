package com.rtoda3.ramidi.facade;

import com.rtoda3.ramidi.SoundFontProperties;
import com.rtoda3.ramidi.converter.IncludeProcessor;
import com.rtoda3.ramidi.converter.MetaCommandParser;
import com.rtoda3.ramidi.converter.RecomposerCompiler;
import com.rtoda3.ramidi.converter.ShorthandProcessor;
import com.rtoda3.ramidi.converter.TextToSmf;
import com.rtoda3.ramidi.core.RamidiException;
import com.rtoda3.ramidi.infra.AudioMasteringProcessor;
import com.rtoda3.ramidi.infra.MidiSynthesizer;
import com.rtoda3.ramidi.support.MessageResolver;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 統合パイプライン・ファサード
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AudioGenerationPipeline {

    private final IncludeProcessor includeProcessor;
    private final ShorthandProcessor shorthandProcessor;
    private final MetaCommandParser metaCommandParser;
    private final TextToSmf textToSmf;
    private final RecomposerCompiler recomposerCompiler;
    private final SoundFontProperties soundFontProperties;
    private final MidiSynthesizer midiSynthesizer;
    private final AudioMasteringProcessor audioMasteringProcessor;
    private final MessageResolver messageResolver;

    public record Generated(byte[] midi, byte[] rawWav, byte[] masteredWav) {

    }

    public Generated generate(Path inputRamidiPath) throws Exception {
        log.info("パイプライン開始: {}", inputRamidiPath);

        try {
            // --- プリプロセス ---
            // INCLUDE展開とオブジェクト化
            var cleaned = includeProcessor.process(inputRamidiPath);
            // 省略記法の展開
            var expanded = shorthandProcessor.process(cleaned);

            // メタ情報とInstructionを構築
            var data = metaCommandParser.parse(expanded);

            // コンパイル (拡張RCP構文 -> SMF構文)
            var smf = recomposerCompiler.compile(data.instructions());

            // アセンブル (ピュアSMFテキスト -> MIDIバイナリ)
            var midi = textToSmf.assemble(smf);

            // オーディオレンダリング (MIDI -> RAW WAV)
            var sfPath = soundFontProperties.resolvePath(data.meta().soundFontAlias());
            var rawWavData = midiSynthesizer.renderMidiToRawWav(midi, sfPath);

            // オーディオマスタリング
            String masteringConfig = data.meta().masteringConfig();
            var masteredWavData = audioMasteringProcessor.masterWav(rawWavData, masteringConfig);

            log.info("パイプライン正常終了");
            return new Generated(midi, rawWavData, masteredWavData);
        } catch (Exception e) {
            String msg = messageResolver.getMessage("error.pipeline.failed", e.getMessage());
            throw new RamidiException(msg, e);
        }
    }
}