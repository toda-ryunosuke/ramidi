package com.rtoda3.ramidi.converter;

import com.rtoda3.ramidi.core.RamidiException;
import com.rtoda3.ramidi.core.RamidiInstruction;
import com.rtoda3.ramidi.support.MessageResolver;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ドラム入力は未実装
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecomposerCompiler {

    private final MessageResolver messageResolver;

    // マーカーの位置を記憶するマップ（ラベル名 -> 絶対Tick数）
    private final Map<String, Long> markers = new HashMap<>();

    public List<RamidiInstruction> compile(List<RamidiInstruction> instructions) {
        var expandedInstructions = preprocess(instructions);
        return transpileToPureSmf(expandedInstructions);
    }

    private List<RamidiInstruction> preprocess(List<RamidiInstruction> instructions) {
        // ★ macroMap はループの外で宣言し、2回目のループ以降もマクロ定義を保持させる
        var macroMap = new HashMap<String, List<RamidiInstruction>>();
        var currentInstructions = instructions;

        // ネストされた CALL_MACRO が無くなるまで最大10回繰り返し展開する
        for (var pass = 0; pass < 10; pass++) {
            var afterMacroInstructions = new ArrayList<RamidiInstruction>();
            var hasCallMacro = false;

            var i = 0;
            while (i < currentInstructions.size()) {
                var instruction = currentInstructions.get(i);
                var cmd = instruction.command();

                if ("DEF_MACRO".equals(cmd) || "DEF_PATTERN".equals(cmd)) {
                    var macroId = instruction.getStringArg(0);
                    var body = new ArrayList<RamidiInstruction>();
                    i++;
                    while (i < currentInstructions.size()) {
                        var subInstruction = currentInstructions.get(i);
                        if ("END_MACRO".equals(subInstruction.command()) || "END_PATTERN".equals(
                            subInstruction.command())) {
                            break;
                        }
                        body.add(subInstruction);
                        i++;
                    }
                    macroMap.put(macroId, body);
                } else if ("CALL_MACRO".equals(cmd) || "CALL_PATTERN".equals(cmd)) {
                    hasCallMacro = true;
                    var macroId = instruction.getStringArg(0);
                    var count = instruction.getIntArg(1);
                    var transpose = instruction.getIntArg(2);
                    var velOffset = instruction.getIntArg(3);

                    var extraArgs = instruction.args().size() > 4
                        ? instruction.args().subList(4, instruction.args().size())
                        : List.<String>of();

                    var body = macroMap.get(macroId);
                    if (body == null) {
                        var msg = messageResolver.getMessage("error.compiler.macro.notdefined",
                            macroId);
                        throw new RamidiException(msg, instruction);
                    }

                    for (var c = 0; c < count; c++) {
                        for (var bodyInstruction : body) {
                            afterMacroInstructions.add(
                                applyMacroArgs(bodyInstruction, transpose, velOffset, extraArgs));
                        }
                    }
                } else {
                    afterMacroInstructions.add(instruction);
                }
                i++;
            }

            currentInstructions = afterMacroInstructions;
            // 展開すべき CALL_MACRO が残っていなければループ終了
            if (!hasCallMacro) {
                break;
            }
        }

        return expandLoops(currentInstructions);
    }

    private RamidiInstruction applyMacroArgs(RamidiInstruction instruction, int transpose,
        int velOffset, List<String> extraArgs) {

        var cmd = instruction.command();
        var rawArgs = instruction.args();

        // 1. 変数引数 ($1, $x1, $X1) の置換処理
        var substitutedArgs = new ArrayList<String>();
        for (var arg : rawArgs) {
            var tempArg = arg;
            for (var k = 0; k < extraArgs.size(); k++) {
                var rawValue = extraArgs.get(k);
                var index = k + 1;

                // 通常置換 ($1)
                tempArg = tempArg.replace("$" + index, rawValue);

                // 16進数フォーマット用数値パース
                var numValue = parseArgToNumber(rawValue);

                // 16進数2桁ゼロ埋め置換 ($x1: 小文字, $X1: 大文字)
                tempArg = tempArg.replace("$x" + index, String.format("%02x", numValue));
                tempArg = tempArg.replace("$X" + index, String.format("%02X", numValue));
            }

            // 置換されずに残っている変数引数を検知して親切なエラーを投げる
            if (tempArg.matches(".*\\$[xX]?[0-9]+.*")) {
                var msg = messageResolver.getMessage("error.compiler.macro.args.missing", tempArg);
                throw new RamidiException(msg, instruction);
            }

            substitutedArgs.add(tempArg);
        }

        var tempInstruction = new RamidiInstruction(instruction, cmd, substitutedArgs);

        // 2. NOTE, CHORD, ARPEGGIO コマンドの場合の移調・ベロシティ計算
        if (("NOTE".equals(cmd) || "CHORD".equals(cmd) || "ARPEGGIO".equals(cmd))
            && substitutedArgs.size() >= 6) {

            // 移調処理
            if ("NOTE".equals(cmd)) {
                var noteStr = tempInstruction.getStringArg(3);
                var note = parseNoteValue(noteStr, tempInstruction) + transpose;
                substitutedArgs.set(3, String.valueOf(note));
            } else {
                // CHORD, ARPEGGIO の場合は文字列を再構築する
                var chordName = tempInstruction.getStringArg(3);
                var transposedChord = transposeChordName(chordName, transpose, tempInstruction);
                substitutedArgs.set(3, transposedChord);
            }

            // ベロシティオフセットの適用（共通）
            var vel = Math.clamp(tempInstruction.getIntArg(4) + velOffset, 1, 127);
            substitutedArgs.set(4, String.valueOf(vel));

            return new RamidiInstruction(instruction, cmd, substitutedArgs);
        }

        return tempInstruction;
    }

    private int parseArgToNumber(String val) {
        try {
            var trimmed = val.trim();
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                return Integer.parseInt(trimmed.substring(2), 16);
            }
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<RamidiInstruction> expandLoops(List<RamidiInstruction> instructions) {
        var result = new ArrayList<RamidiInstruction>();
        var loopStack = new ArrayDeque<LoopBlock>();

        for (var instruction : instructions) {
            var cmd = instruction.command();

            if ("LOOP_START".equals(cmd)) {
                var count = instruction.getIntArg(0);
                var block = new LoopBlock(count, instruction);
                if (!loopStack.isEmpty()) {
                    loopStack.peek().children.add(block);
                }
                loopStack.push(block);
            } else if ("LOOP_END".equals(cmd)) {
                if (loopStack.isEmpty()) {
                    var msg = messageResolver.getMessage("error.compiler.loop.no_start");
                    throw new RamidiException(msg, instruction);
                }
                var finished = loopStack.pop();
                if (loopStack.isEmpty()) {
                    result.addAll(finished.flatten(0));
                }
            } else {
                if (!loopStack.isEmpty()) {
                    loopStack.peek().children.add(instruction);
                } else {
                    result.add(instruction);
                }
            }
        }
        if (!loopStack.isEmpty()) {
            var msg = messageResolver.getMessage("error.compiler.loop.no_end");
            throw new RamidiException(msg, loopStack.peek().instruction);
        }
        return result;
    }

    private List<RamidiInstruction> transpileToPureSmf(List<RamidiInstruction> instructions) {
        var pureInstructions = new ArrayList<RamidiInstruction>();
        var trackTicks = new long[128];
        var trackKeyShift = new int[128];

        for (var instruction : instructions) {
            var cmd = instruction.command();
            var args = instruction.args();

            try {
                switch (cmd) {
                    case "TITLE" -> {
                        var title = instruction.getStringArg(0);
                        pureInstructions.add(new RamidiInstruction(instruction, "META_TEXT",
                            List.of("0", "0", "3", title)));
                    }
                    case "TRACK_NAME" -> {
                        var trk = instruction.getIntArg(0);
                        var name = instruction.getStringArg(1);
                        pureInstructions.add(new RamidiInstruction(instruction, "META_TEXT",
                            List.of(String.valueOf(trk), "0", "3", name)));
                    }
                    case "COPYRIGHT" -> {
                        var text = instruction.getStringArg(0);
                        pureInstructions.add(new RamidiInstruction(instruction, "META_TEXT",
                            List.of("0", "0", "2", text)));
                    }
                    case "KEY_SHIFT" -> {
                        var trk = instruction.getIntArg(0);
                        trackKeyShift[trk] = instruction.getIntArg(1);
                    }
                    case "NOTE" -> {
                        var trk = instruction.getIntArg(0);
                        var ch = instruction.getIntArg(1);
                        var st = instruction.getLongArg(2);
                        var noteStr = instruction.getStringArg(3);
                        var note = parseNoteValue(noteStr, instruction) + trackKeyShift[trk];
                        var vel = instruction.getIntArg(4);
                        var gate = instruction.getLongArg(5);
                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        pureInstructions.add(new RamidiInstruction(instruction, "NOTE",
                            List.of(String.valueOf(trk), String.valueOf(ch),
                                String.valueOf(trackTicks[trk]), String.valueOf(note),
                                String.valueOf(vel), String.valueOf(gate))));
                    }
                    case "CHORD" -> {
                        var trk = instruction.getIntArg(0);
                        var ch = instruction.getIntArg(1);
                        var st = instruction.getLongArg(2);
                        var chordName = instruction.getStringArg(3);
                        var vel = instruction.getIntArg(4);
                        var gate = instruction.getLongArg(5);
                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        var notes = parseChordName(chordName, instruction);
                        for (var note : notes) {
                            pureInstructions.add(new RamidiInstruction(instruction, "NOTE",
                                List.of(String.valueOf(trk), String.valueOf(ch),
                                    String.valueOf(trackTicks[trk]),
                                    String.valueOf(note + trackKeyShift[trk]), String.valueOf(vel),
                                    String.valueOf(gate))));
                        }
                    }
                    case "ARPEGGIO" -> {
                        var trk = instruction.getIntArg(0);
                        var ch = instruction.getIntArg(1);
                        var st = instruction.getLongArg(2);
                        var chordName = instruction.getStringArg(3);
                        var vel = instruction.getIntArg(4);
                        var gate = instruction.getLongArg(5);
                        var arpDelay = instruction.getLongArg(6);
                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        var notes = parseChordName(chordName, instruction);
                        for (var i = 0; i < notes.length; i++) {
                            pureInstructions.add(new RamidiInstruction(instruction, "NOTE",
                                List.of(String.valueOf(trk), String.valueOf(ch),
                                    String.valueOf(trackTicks[trk] + (i * arpDelay)),
                                    String.valueOf(notes[i] + trackKeyShift[trk]),
                                    String.valueOf(vel), String.valueOf(gate))));
                        }
                    }
                    case "SWEEP_CC" -> {
                        var trk = instruction.getIntArg(0);
                        var ch = instruction.getIntArg(1);
                        var st = instruction.getLongArg(2);
                        var duration = instruction.getLongArg(3);
                        var ccNum = instruction.getIntArg(4);
                        var startVal = instruction.getIntArg(5);
                        var endVal = instruction.getIntArg(6);
                        var step = instruction.getLongArg(7);

                        if (duration <= 0 || step <= 0) {
                            var msg = messageResolver.getMessage("error.compiler.sweep.invalid",
                                duration, step);
                            throw new RamidiException(msg, instruction);
                        }

                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        var startTick = trackTicks[trk];
                        for (var t = 0L; t <= duration; t += step) {
                            var progress = (double) t / duration;
                            var currentVal = (int) Math.round(
                                startVal + (endVal - startVal) * progress);
                            pureInstructions.add(new RamidiInstruction(instruction, "CC",
                                List.of(String.valueOf(trk), String.valueOf(ch),
                                    String.valueOf(startTick + t), String.valueOf(ccNum),
                                    String.valueOf(currentVal))));
                        }
                    }
                    case "SWEEP_BEND" -> {
                        var trk = instruction.getIntArg(0);
                        var ch = instruction.getIntArg(1);
                        var st = instruction.getLongArg(2);
                        var duration = instruction.getLongArg(3);
                        var startVal = instruction.getIntArg(4);
                        var endVal = instruction.getIntArg(5);
                        var step = instruction.getLongArg(6);

                        if (duration <= 0 || step <= 0) {
                            var msg = messageResolver.getMessage("error.compiler.sweep.invalid",
                                duration, step);
                            throw new RamidiException(msg, instruction);
                        }

                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        var startTick = trackTicks[trk];
                        for (var t = 0L; t <= duration; t += step) {
                            var progress = (double) t / duration;
                            var currentVal = (int) Math.round(
                                startVal + (endVal - startVal) * progress);
                            pureInstructions.add(new RamidiInstruction(instruction, "BEND",
                                List.of(String.valueOf(trk), String.valueOf(ch),
                                    String.valueOf(startTick + t), String.valueOf(currentVal))));
                        }
                    }
                    case "SWEEP_TEMPO" -> {
                        var trk = instruction.getIntArg(0);
                        var st = instruction.getLongArg(1);
                        var duration = instruction.getLongArg(2);
                        var startBpm = instruction.getDoubleArg(3);
                        var endBpm = instruction.getDoubleArg(4);
                        var step = instruction.getLongArg(5);

                        if (duration <= 0 || step <= 0) {
                            var msg = messageResolver.getMessage("error.compiler.sweep.invalid",
                                duration, step);
                            throw new RamidiException(msg, instruction);
                        }

                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        var startTick = trackTicks[trk];
                        for (var t = 0L; t <= duration; t += step) {
                            var progress = (double) t / duration;
                            var currentBpm = startBpm + (endBpm - startBpm) * progress;
                            pureInstructions.add(new RamidiInstruction(instruction, "TEMPO",
                                List.of(String.valueOf(trk), String.valueOf(startTick + t),
                                    String.format("%.2f", currentBpm))));
                        }
                    }
                    case "MARKER" -> {
                        // MARKER, trk, label
                        var trk = instruction.getIntArg(0);
                        var label = instruction.getStringArg(1).trim();
                        // 現在のトラックの絶対Tickをラベルとして記憶
                        markers.put(label, trackTicks[trk]);
                    }
                    case "SYNC" -> {
                        // SYNC, trk, label
                        var trk = instruction.getIntArg(0);
                        var label = instruction.getStringArg(1).trim();

                        if (!markers.containsKey(label)) {
                            var msg = messageResolver.getMessage("error.compiler.marker.notfound",
                                label);
                            throw new RamidiException(msg, instruction); // または適当なエラーメッセージ
                        }
                        // トラックの絶対時間をマーカーの位置にワープ（同期）させる
                        trackTicks[trk] = markers.get(label);
                    }
                    case "WAIT" -> {
                        // WAIT, trk, ST
                        var trk = instruction.getIntArg(0);
                        var st = instruction.getIntArg(1);

                        // 指定トラックの時間をST分だけ進める（音は鳴らさない）
                        trackTicks[trk] += st;
                    }
                    case "TEMPO" -> {
                        var trk = instruction.getIntArg(0);
                        var st = instruction.getLongArg(1);
                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        pureInstructions.add(new RamidiInstruction(instruction, "TEMPO",
                            List.of(String.valueOf(trk), String.valueOf(trackTicks[trk]),
                                args.get(2))));
                    }
                    case "TIMESIG" -> {
                        var trk = instruction.getIntArg(0);
                        var st = instruction.getLongArg(1);
                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        pureInstructions.add(new RamidiInstruction(instruction, "TIMESIG",
                            List.of(String.valueOf(trk), String.valueOf(trackTicks[trk]),
                                args.get(2), args.get(3))));
                    }
                    case "KEYSIG" -> {
                        var trk = instruction.getIntArg(0);
                        var st = instruction.getLongArg(1);
                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        pureInstructions.add(new RamidiInstruction(instruction, "KEYSIG",
                            List.of(String.valueOf(trk), String.valueOf(trackTicks[trk]),
                                args.get(2), args.get(3))));
                    }
                    case "META_TEXT" -> {
                        var trk = instruction.getIntArg(0);
                        var st = instruction.getLongArg(1);
                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        pureInstructions.add(new RamidiInstruction(instruction, "META_TEXT",
                            List.of(String.valueOf(trk), String.valueOf(trackTicks[trk]),
                                args.get(2), args.get(3))));
                    }
                    case "SYSEX" -> {
                        var trk = instruction.getIntArg(0);
                        var st = instruction.getLongArg(1);
                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        pureInstructions.add(new RamidiInstruction(instruction, "SYSEX",
                            List.of(String.valueOf(trk), String.valueOf(trackTicks[trk]),
                                args.get(2))));
                    }
                    case "CC" -> {
                        var trk = instruction.getIntArg(0);
                        var ch = instruction.getIntArg(1);
                        var st = instruction.getLongArg(2);
                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        pureInstructions.add(new RamidiInstruction(instruction, "CC",
                            List.of(String.valueOf(trk), String.valueOf(ch),
                                String.valueOf(trackTicks[trk]), args.get(3), args.get(4))));
                    }
                    case "PROGRAM" -> {
                        var trk = instruction.getIntArg(0);
                        var ch = instruction.getIntArg(1);
                        var st = instruction.getLongArg(2);
                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        pureInstructions.add(new RamidiInstruction(instruction, "PROGRAM",
                            List.of(String.valueOf(trk), String.valueOf(ch),
                                String.valueOf(trackTicks[trk]), args.get(3))));
                    }
                    case "BEND" -> {
                        var trk = instruction.getIntArg(0);
                        var ch = instruction.getIntArg(1);
                        var st = instruction.getLongArg(2);
                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        pureInstructions.add(new RamidiInstruction(instruction, "BEND",
                            List.of(String.valueOf(trk), String.valueOf(ch),
                                String.valueOf(trackTicks[trk]), args.get(3))));
                    }
                    case "POLY_PRESS" -> {
                        var trk = instruction.getIntArg(0);
                        var ch = instruction.getIntArg(1);
                        var st = instruction.getLongArg(2);
                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        pureInstructions.add(
                            new RamidiInstruction(instruction, "POLY_PRESS",
                                List.of(String.valueOf(trk), String.valueOf(ch),
                                    String.valueOf(trackTicks[trk]), args.get(3), args.get(4))));
                    }
                    case "CHAN_PRESS" -> {
                        var trk = instruction.getIntArg(0);
                        var ch = instruction.getIntArg(1);
                        var st = instruction.getLongArg(2);
                        trackTicks[trk] = Math.max(0, trackTicks[trk] + st);
                        pureInstructions.add(
                            new RamidiInstruction(instruction, "CHAN_PRESS",
                                List.of(String.valueOf(trk), String.valueOf(ch),
                                    String.valueOf(trackTicks[trk]), args.get(3))));
                    }
                    case "SET_TICK" -> trackTicks[instruction.getIntArg(0)] = Math.max(0,
                        instruction.getLongArg(1));
                    default -> pureInstructions.add(instruction);
                }
            } catch (IndexOutOfBoundsException e) {
                var msg = messageResolver.getMessage("error.compiler.args.missing", cmd);
                throw new RamidiException(msg, instruction, e);
            }
        }
        return pureInstructions;
    }

    private int[] parseChordName(String name, RamidiInstruction instruction) {
        var p = Pattern.compile("^([A-Ga-g][\\+\\-]?)([0-8])?(.*)$");
        var m = p.matcher(name);
        if (!m.matches()) {
            var msg = messageResolver.getMessage("error.compiler.chord.invalid", name);
            throw new RamidiException(msg, instruction);
        }
        var noteName = m.group(1).toUpperCase();
        var octave = m.group(2) != null ? Integer.parseInt(m.group(2)) : 4;
        var type = m.group(3).toLowerCase();
        var root = (octave + 1) * 12 + getNoteOffset(noteName, instruction);
        var offsets = switch (type) {
            case "", "maj" -> new int[]{0, 4, 7};
            case "min", "m" -> new int[]{0, 3, 7};
            case "6" -> new int[]{0, 4, 7, 9};                 // ★ 追加 (メジャー6)
            case "min6", "m6" -> new int[]{0, 3, 7, 9};        // ★ 追加 (マイナー6)
            case "maj7" -> new int[]{0, 4, 7, 11};
            case "min7", "m7" -> new int[]{0, 3, 7, 10};
            case "7" -> new int[]{0, 4, 7, 10};
            case "add9" -> new int[]{0, 4, 7, 14};
            case "madd9", "minadd9" -> new int[]{0, 3, 7, 14};
            case "sus4" -> new int[]{0, 5, 7};
            case "7sus4" -> new int[]{0, 5, 7, 10};
            case "dim" -> new int[]{0, 3, 6};
            case "dim7" -> new int[]{0, 3, 6, 9};
            case "aug" -> new int[]{0, 4, 8};
            default -> {
                var msg = messageResolver.getMessage("error.compiler.chord.type.unsupported", type);
                throw new RamidiException(msg, instruction);
            }
        };
        return Arrays.stream(offsets).map(o -> root + o).toArray();
    }

    private int getNoteOffset(String note, RamidiInstruction instruction) {
        return switch (note) {
            case "C", "B+" -> 0;
            case "C+", "D-" -> 1;
            case "D" -> 2;
            case "D+", "E-" -> 3;
            case "E", "F-" -> 4;
            case "F", "E+" -> 5;
            case "F+", "G-" -> 6;
            case "G" -> 7;
            case "G+", "A-" -> 8;
            case "A" -> 9;
            case "A+", "B-" -> 10;
            case "B", "C-" -> 11;
            default -> {
                var msg = messageResolver.getMessage("error.compiler.chord.note.unsupported", note);
                throw new RamidiException(msg, instruction);
            }
        };
    }

    private int parseNoteValue(String noteStr, RamidiInstruction instruction) {
        // まず数値としてパースを試みる
        try {
            var trimmed = noteStr.trim();
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                return Integer.parseInt(trimmed.substring(2), 16);
            }
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            // 数値じゃなければ音階名 (例: C4, F+2) としてパース
            var p = Pattern.compile("^([A-Ga-g][\\+\\-]?)([0-8])?$");
            var m = p.matcher(noteStr.trim());
            if (!m.matches()) {
                var msg = messageResolver.getMessage("error.compiler.note.invalid", noteStr);
                throw new RamidiException(msg, instruction);
            }
            var noteName = m.group(1).toUpperCase();
            var octave = m.group(2) != null ? Integer.parseInt(m.group(2)) : 4; // 省略時は4
            return (octave + 1) * 12 + getNoteOffset(noteName, instruction);
        }
    }

    private String transposeChordName(String chordName, int transpose,
        RamidiInstruction instruction) {
        if (transpose == 0) {
            return chordName;
        }

        var p = Pattern.compile("^([A-Ga-g][\\+\\-]?)([0-8])?(.*)$");
        var m = p.matcher(chordName);
        if (!m.matches()) {
            return chordName;
        }

        var noteName = m.group(1).toUpperCase();
        var octaveStr = m.group(2);
        var octave = octaveStr != null ? Integer.parseInt(octaveStr) : 4;
        var type = m.group(3);

        var root = (octave + 1) * 12 + getNoteOffset(noteName, instruction);
        root += transpose;

        // 負数移調に対応した切り捨て除算と余り計算
        var newOctave = Math.floorDiv(root, 12) - 1;
        var noteIndex = Math.floorMod(root, 12);

        var newNoteName = switch (noteIndex) {
            case 0 -> "C";
            case 1 -> "C+";
            case 2 -> "D";
            case 3 -> "D+";
            case 4 -> "E";
            case 5 -> "F";
            case 6 -> "F+";
            case 7 -> "G";
            case 8 -> "G+";
            case 9 -> "A";
            case 10 -> "A+";
            case 11 -> "B";
            default -> "C";
        };

        return newNoteName + newOctave + type;
    }

    @RequiredArgsConstructor
    private static class LoopBlock {

        private final int totalCount;
        private final RamidiInstruction instruction;
        private final List<Object> children = new ArrayList<>();

        List<RamidiInstruction> flatten(int currentDepth) {
            var result = new ArrayList<RamidiInstruction>();
            for (var i = 0; i < totalCount; i++) {
                var isLast = (i == totalCount - 1);
                var execute = true;
                for (var child : children) {
                    if (child instanceof RamidiInstruction childInstruction) {
                        var cmd = childInstruction.command();
                        if ("LOOP_LAST".equals(cmd)) {
                            execute = isLast;
                            continue;
                        }
                        if ("LOOP_NOT_LAST".equals(cmd)) {
                            execute = !isLast;
                            continue;
                        }
                        if (execute) {
                            result.add(childInstruction);
                        }
                    } else if (child instanceof LoopBlock inner) {
                        if (execute) {
                            result.addAll(inner.flatten(currentDepth + 1));
                        }
                    }
                }
            }
            return result;
        }
    }
}
