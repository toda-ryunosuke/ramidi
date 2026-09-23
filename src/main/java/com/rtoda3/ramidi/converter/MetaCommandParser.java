package com.rtoda3.ramidi.converter;

import com.rtoda3.ramidi.core.RamidiException;
import com.rtoda3.ramidi.core.RamidiInstruction;
import com.rtoda3.ramidi.support.MessageResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MetaCommandParser {

    private final MessageResolver messageResolver;

    public record RamidiData(RamidiMeta meta, List<RamidiInstruction> instructions) {

    }

    public record RamidiMeta(String soundFontAlias, String masteringConfig) {

    }

    private static final Map<String, String> MASTERING_PRESETS = Map.of(
        // クラブミュージック向け（音圧高め・コンプ強め）
        "EDM",
        "acompressor=threshold=-12dB:ratio=4:attack=5:release=50,loudnorm=I=-14:LRA=11:TP=-1.5,silenceremove=stop_periods=1:stop_duration=0.2:stop_threshold=-60dB",
        // ポップス向け（標準的・少し緩めのコンプ）
        "POP",
        "acompressor=threshold=-15dB:ratio=3:attack=10:release=100,loudnorm=I=-16:LRA=13:TP=-1.5,silenceremove=stop_periods=1:stop_duration=0.2:stop_threshold=-60dB:",
        // オーケストラ・ピアノ向け（コンプなし、ダイナミクスを活かしつつラウドネス規格に合わせる）
        "CLASSIC",
        "loudnorm=I=-23:LRA=20:TP=-2.0,silenceremove=stop_periods=1:stop_duration=0.2:stop_threshold=-60dB",
        // 8bit/レトロゲーム風（サンプリングレートを下げて少し歪ませる）
        "LOFI",
        "aresample=11025,aformat=sample_fmts=u8,acompressor=threshold=-15dB:ratio=4,loudnorm=I=-14:LRA=11:TP=-1.5,silenceremove=stop_periods=1:stop_duration=0.2:stop_threshold=-60dB",
        // マスタリングなし（FluidSynthのRAW出力そのまま）
        "NONE", ""
    );

    private static final String DEFAULT_SOUND_FONT_ALIAS = "GeneralUser";
    private static final String DEFAULT_MASTERING_TYPE = "EDM";

    public RamidiData parse(List<RamidiInstruction> instructions) {
        var soundFontAlias = DEFAULT_SOUND_FONT_ALIAS;
        var masteringType = DEFAULT_MASTERING_TYPE;
        var remainingInstructions = new ArrayList<RamidiInstruction>();

        for (var instruction : instructions) {
            switch (instruction.command()) {
                case "USE_SOUNDFONT" -> {
                    if (instruction.args().isEmpty() || instruction.args().getFirst().trim()
                        .isEmpty()) {
                        var msg = messageResolver.getMessage("error.meta.soundfont.required");
                        throw new RamidiException(msg, instruction);
                    }
                    soundFontAlias = instruction.args().getFirst().trim();
                }
                case "MASTERING_TYPE" -> {
                    if (instruction.args().isEmpty() || instruction.args().getFirst().trim()
                        .isEmpty()) {
                        var msg = messageResolver.getMessage("error.meta.mastering.required");
                        throw new RamidiException(msg, instruction);
                    }
                    masteringType = instruction.args().getFirst().trim().toUpperCase();
                    if (!MASTERING_PRESETS.containsKey(masteringType)) {
                        var msg = messageResolver.getMessage("error.meta.mastering.invalid",
                            masteringType, MASTERING_PRESETS.keySet().toString());
                        throw new RamidiException(msg, instruction);
                    }
                }
                default -> remainingInstructions.add(instruction);
            }
        }

        var masteringFilter = MASTERING_PRESETS.get(masteringType);
        return new RamidiData(
            new RamidiMeta(soundFontAlias, masteringFilter),
            remainingInstructions);
    }
}