package com.rtoda3.ramidi.core;

public class RamidiException extends RuntimeException {

    private final RamidiInstruction instruction;

    public RamidiException(String message) {
        super(message);
        instruction = null;
    }

    public RamidiException(String message, RamidiInstruction instruction) {
        super(message);
        this.instruction = instruction;
    }

    public RamidiException(String message, Throwable cause) {
        super(message, cause);
        instruction = null;
    }

    public RamidiException(String message, RamidiInstruction instruction, Throwable cause) {
        super(message, cause);
        this.instruction = instruction;
    }

    @Override
    public String getMessage() {
        if (instruction != null) {
            return String.format(
                "構文エラー [%s:%d]\n  > 行内容: \"%s\"\n  > 詳細: %s",
                instruction.sourcePath(),
                instruction.lineNumber(),
                instruction.rawText(),
                super.getMessage()
            );
        }
        return super.getMessage();
    }
}