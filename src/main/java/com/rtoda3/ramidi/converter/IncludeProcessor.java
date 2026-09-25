package com.rtoda3.ramidi.converter;

import com.rtoda3.ramidi.core.RamidiException;
import com.rtoda3.ramidi.core.RamidiInstruction;
import com.rtoda3.ramidi.support.MessageResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 組み込み系ディレクティブを再帰的に解決し、すべてのファイルの内容をRamidiLineのリストとしてまとめる責務を負います。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IncludeProcessor {

    private final MessageResolver messageResolver;

    // ユーザープリセットの置き場所
    @Value("${ramidi.preset.directory:./user_presets/}")
    private String presetDirectory;

    public List<RamidiInstruction> process(Path rootPath) {
        var visited = new HashSet<Path>();
        return processRecursive(rootPath, visited);
    }

    private List<RamidiInstruction> processRecursive(Path currentPath, Set<Path> visited) {
        var normalizedPath = currentPath.toAbsolutePath().normalize();

        // 循環参照の防止と警告
        if (visited.contains(normalizedPath)) {
            var warnMsg = messageResolver.getMessage("warn.include.circular", normalizedPath);
            log.warn(warnMsg);
            return List.of();
        }
        visited.add(normalizedPath);

        if (!Files.exists(normalizedPath)) {
            var msg = messageResolver.getMessage("error.include.notfound", normalizedPath);
            throw new RamidiException(msg);
        }

        List<String> rawLines;
        try {
            rawLines = Files.readAllLines(normalizedPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            var msg = messageResolver.getMessage("error.include.readfailed", normalizedPath);
            throw new RamidiException(msg, e);
        }

        var resultLines = new ArrayList<RamidiInstruction>();
        var parentDir = normalizedPath.getParent();
        var presetRootDir = Path.of(presetDirectory).toAbsolutePath().normalize();

        for (var i = 0; i < rawLines.size(); i++) {
            var instruction = new RamidiInstruction(normalizedPath, i + 1, rawLines.get(i));
            var cmd = instruction.command();
            var args = instruction.args();

            if ("INCLUDE".equals(cmd)) {
                if (args.isEmpty() || args.getFirst().isBlank()) {
                    var msg = messageResolver.getMessage("error.include.args.missing");
                    throw new RamidiException(msg, instruction);
                }

                var pathStr = args.getFirst().endsWith(".ramidi")
                    ? args.getFirst()
                    : args.getFirst() + ".ramidi";
                var childPath = (parentDir != null)
                    ? parentDir.resolve(pathStr)
                    : Path.of(pathStr);

                // 子ファイルの内容を再帰的に読み込んで展開挿入
                resultLines.addAll(processRecursive(childPath, new HashSet<>(visited)));

            } else if ("USER_PRESET".equals(cmd)) {
                if (args.isEmpty() || args.getFirst().isBlank()) {
                    var msg = messageResolver.getMessage("error.user_preset.args.missing");
                    throw new RamidiException(msg, instruction);
                }

                var pathStr = args.getFirst().endsWith(".ramidi")
                    ? args.getFirst()
                    : args.getFirst() + ".ramidi";
                var childPath = presetRootDir.resolve(pathStr);

                // 子ファイルの内容を再帰的に読み込んで展開挿入
                resultLines.addAll(processRecursive(childPath, new HashSet<>(visited)));

            } else if (!instruction.isEmpty()) {
                resultLines.add(instruction);
            }
        }

        return resultLines;
    }
}