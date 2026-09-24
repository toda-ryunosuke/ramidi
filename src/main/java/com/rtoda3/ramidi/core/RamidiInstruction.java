package com.rtoda3.ramidi.core;

import static java.util.stream.IntStream.range;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class RamidiInstruction {

    private final Path sourcePath;
    private final Integer lineNumber;
    /** 無加工のテキスト */
    private final String rawText;

    // 導出値
    private String command;
    private List<String> args;

    public RamidiInstruction(Path sourcePath, Integer lineNumber, String rawText) {
        this.sourcePath = sourcePath;
        this.lineNumber = lineNumber;
        this.rawText = rawText;

        var cleanedText = rawText.split("#")[0].trim();
        var parts = cleanedText.split(",", -1); // 末尾の空文字列も保持

        this.command = parts[0].trim().replaceAll("^\"|\"$", "").toUpperCase();

        this.args = parts.length < 2
            ? List.of()
            : Arrays.stream(Arrays.copyOfRange(parts, 1, parts.length))
                .map(String::trim)
                .map(s -> s.replaceAll("^\"|\"$", ""))
                .toList();
    }

    /**
     * 省略された項目を指定されたオブジェクトの値と同じに補完した新しいオブジェクトを生成するコンストラクタ（ショートハンド用）
     *
     * @param instruction 省略ありオブジェクト
     * @param prev 補完する値を持つオブジェクト
     */
    public RamidiInstruction(RamidiInstruction instruction, RamidiInstruction prev) {
        this(instruction.sourcePath(), instruction.lineNumber(), instruction.rawText());

        this.command = this.command.isEmpty() ? prev.command : this.command;

        this.args = range(0, this.args.size())
            .mapToObj(i -> {
                var currentArg = this.args.get(i);
                // 空文字 かつ 前行に同じ位置の引数が存在する場合のみ引き継ぐ
                if (currentArg.isEmpty() && i < prev.args().size()) {
                    return prev.args().get(i);
                } else {
                    return currentArg;
                }
            })
            .toList();
    }

    /**
     * 引数で指定されたオブジェクトのコマンドと引数を置き換えた新しいオブジェクトを生成するコンストラクタ
     */
    public RamidiInstruction(RamidiInstruction instruction, String command, List<String> args) {
        this(instruction.sourcePath(), instruction.lineNumber(), instruction.rawText());
        this.command = command;
        this.args = args;
    }

    public Path sourcePath() {
        return this.sourcePath;
    }

    public Integer lineNumber() {
        return this.lineNumber;
    }

    public String rawText() {
        return this.rawText;
    }

    public String command() {
        return this.command;
    }

    public List<String> args() {
        return this.args;
    }

    public boolean isEmpty() {
        return command.isEmpty() && args.isEmpty();
    }

    public String getStringArg(int index) {
        if (args.size() <= index) {
            throw new RamidiException(String.format("パラメータ %d が不足しています。", index),
                this);
        }
        return args.get(index);
    }

    public int getIntArg(int index) {
        var value = getStringArg(index);
        try {
            return parseNumber(value);
        } catch (NumberFormatException e) {
            throw new RamidiException(
                String.format("パラメータ %d ('%s') は数値である必要があります。", index, value),
                this, e);
        }
    }

    public long getLongArg(int index) {
        var value = getStringArg(index);
        try {
            return parseLongNumber(value);
        } catch (NumberFormatException e) {
            throw new RamidiException(
                String.format("パラメータ %d ('%s') は数値である必要があります。", index, value),
                this, e);
        }
    }

    public double getDoubleArg(int index) {
        var value = getStringArg(index);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new RamidiException(
                String.format("パラメータ %d は数値である必要があります。", index), this, e);
        }
    }

    private int parseNumber(String value) {
        var trimmed = value.trim();
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return Integer.parseInt(trimmed.substring(2), 16);
        }
        return Integer.parseInt(trimmed);
    }

    private long parseLongNumber(String value) {
        var trimmed = value.trim();
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return Long.parseLong(trimmed.substring(2), 16);
        }
        return Long.parseLong(trimmed);
    }
}
