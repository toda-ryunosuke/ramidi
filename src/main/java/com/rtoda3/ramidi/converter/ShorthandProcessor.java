package com.rtoda3.ramidi.converter;

import static java.util.stream.IntStream.range;

import com.rtoda3.ramidi.core.RamidiInstruction;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * パラメータの継承（省略記法）を解決する
 */
@Service
public class ShorthandProcessor {

    public List<RamidiInstruction> process(List<RamidiInstruction> cleanedLines) {
        if (cleanedLines.isEmpty()) {
            return cleanedLines;
        }

        var remainingInstructions = new ArrayList<RamidiInstruction>();
        remainingInstructions.add(cleanedLines.getFirst());

        range(1, cleanedLines.size()).forEach(i -> {
            var current = cleanedLines.get(i);
            var prev = remainingInstructions.get(i - 1);

            // 現在行が前行と同じ項目を継承する
            remainingInstructions.add(new RamidiInstruction(current, prev));
        });

        return remainingInstructions;
    }
}