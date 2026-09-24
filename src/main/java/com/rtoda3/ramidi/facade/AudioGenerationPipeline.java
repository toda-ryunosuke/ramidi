package com.rtoda3.ramidi.facade;

import com.rtoda3.ramidi.SoundFontProperties;
import com.rtoda3.ramidi.converter.IncludeProcessor;
import com.rtoda3.ramidi.converter.MetaCommandParser;
import com.rtoda3.ramidi.converter.RecomposerCompiler;
import com.rtoda3.ramidi.converter.ShorthandProcessor;
import com.rtoda3.ramidi.converter.TextToSmf;
import com.rtoda3.ramidi.infra.AudioMasteringProcessor;
import com.rtoda3.ramidi.infra.AudioToVideoProcessor;
import com.rtoda3.ramidi.infra.MidiSynthesizer;
import com.rtoda3.ramidi.support.MessageResolver;
import java.nio.file.Path;
import java.util.Optional;
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

    private final MessageResolver messageResolver;

    private final IncludeProcessor includeProcessor;
    private final ShorthandProcessor shorthandProcessor;
    private final MetaCommandParser metaCommandParser;
    private final TextToSmf textToSmf;
    private final RecomposerCompiler recomposerCompiler;
    private final SoundFontProperties soundFontProperties;
    private final MidiSynthesizer midiSynthesizer;
    private final AudioMasteringProcessor audioMasteringProcessor;
    private final AudioToVideoProcessor audioToVideoProcessor;

    public enum TargetStage {
        MIDI,           // MIDI生成までで終了
        RAW_WAV,        // SoundFontレンダリングまでで終了
        MASTERED_WAV,   // マスタリング処理までで終了
        MP4             // 動画生成まですべて実行（デフォルト）
        ;

        public static TargetStage fromString(String value) {
            if (value == null || value.isBlank()) {
                return MP4; // デフォルトは最後まで
            }
            return switch (value.toLowerCase()) {
                case "midi" -> MIDI;
                case "raw" -> RAW_WAV;
                case "master" -> MASTERED_WAV;
                case "mp4" -> MP4;
                default -> throw new IllegalArgumentException("無効なステージ指定です: " + value);
            };
        }
    }

    public record PipelineCommand(Path inputRamidiPath, Path coverPath, TargetStage targetStage) {

    }

    public record Generated(
        Optional<byte[]> midi,
        Optional<byte[]> rawWav,
        Optional<byte[]> masteredWav,
        Optional<byte[]> mp4) {

    }

    public Generated generate(PipelineCommand command) {
        log.info("パイプライン開始: {}", command.inputRamidiPath);

        // --- プリプロセス ---
        // INCLUDE展開とオブジェクト化
        var cleaned = includeProcessor.process(command.inputRamidiPath);
        // 省略記法の展開
        var expanded = shorthandProcessor.process(cleaned);

        // メタ情報とInstructionを構築
        var data = metaCommandParser.parse(expanded);

        // コンパイル (拡張RCP構文 -> SMF構文)
        var smf = recomposerCompiler.compile(data.instructions());

        // アセンブル (ピュアSMFテキスト -> MIDIバイナリ)
        var midi = textToSmf.assemble(smf);

        if (command.targetStage() == TargetStage.MIDI) {
            log.info("TargetStage MIDI に到達したため処理を終了します");
            return new Generated(Optional.of(midi), Optional.empty(), Optional.empty(),
                Optional.empty());
        }

        // オーディオレンダリング (MIDI -> RAW WAV)
        var sfPath = soundFontProperties.resolvePath(data.meta().soundFontAlias());
        var rawWavData = midiSynthesizer.renderMidiToRawWav(midi, sfPath);

        if (command.targetStage() == TargetStage.RAW_WAV) {
            log.info("TargetStage RAW_WAV に到達したため処理を終了します");
            return new Generated(Optional.of(midi), Optional.of(rawWavData), Optional.empty(),
                Optional.empty());
        }

        // オーディオマスタリング
        var masteringConfig = data.meta().masteringConfig();
        var masteredWavData = audioMasteringProcessor.masterWav(rawWavData, masteringConfig);

        if (command.targetStage() == TargetStage.MASTERED_WAV) {
            log.info("TargetStage MASTERED_WAV に到達したため処理を終了します");
            return new Generated(Optional.of(midi), Optional.of(rawWavData),
                Optional.of(masteredWavData), Optional.empty());
        }

        // cover.png が存在すれば MP4 を生成
        var mp4Data = audioToVideoProcessor.generateMp4(command.coverPath, masteredWavData);

        log.info("パイプライン正常終了 (全ステージ完了)");
        return new Generated(Optional.of(midi), Optional.of(rawWavData),
            Optional.of(masteredWavData), Optional.ofNullable(mp4Data));

    }
}
