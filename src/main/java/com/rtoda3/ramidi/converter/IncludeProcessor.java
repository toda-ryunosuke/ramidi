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
import org.springframework.stereotype.Service;

/**
 * INCLUDEディレクティブを再帰的に解決し、すべてのファイルの内容をRamidiLineのリストとしてまとめる責務を負います。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IncludeProcessor {

    private final MessageResolver messageResolver;

    public List<RamidiInstruction> process(Path rootPath) {
        var visited = new HashSet<Path>();
        return processRecursive(rootPath, visited);
    }

    private List<RamidiInstruction> processRecursive(Path currentPath, Set<Path> visited) {
        var normalizedPath = currentPath.toAbsolutePath().normalize();

        // 循環参照の防止
        if (visited.contains(normalizedPath)) {
            log.warn("Circular reference detected for include: {}. Skipping.", normalizedPath);
            return List.of();
        }
        visited.add(normalizedPath);

        if (!Files.exists(normalizedPath)) {
            String msg = messageResolver.getMessage("error.include.notfound", normalizedPath);
            throw new RamidiException(msg);
        }

        List<String> rawLines;
        try {
            rawLines = Files.readAllLines(normalizedPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            String msg = messageResolver.getMessage("error.include.readfailed", normalizedPath);
            throw new RamidiException(msg, e);
        }

        var resultLines = new ArrayList<RamidiInstruction>();
        var parentDir = normalizedPath.getParent();

        for (int i = 0; i < rawLines.size(); i++) {
            var line = rawLines.get(i);
            var lineNumber = i + 1;

            var ramidiInstruction = new RamidiInstruction(normalizedPath, lineNumber, line);

            // INCLUDEコマンドかどうかをにチェック
            if ("INCLUDE".equals(ramidiInstruction.command())
                && !ramidiInstruction.args().isEmpty()) {
                var relativePathStr = ramidiInstruction.args().getFirst();
                var childPath = (parentDir != null)
                    ? parentDir.resolve(relativePathStr)
                    : Path.of(relativePathStr);

                // 子ファイルの内容を再帰的に読み込んで展開挿入
                var childLines = processRecursive(childPath, new HashSet<>(visited));
                resultLines.addAll(childLines);
            } else {
                // INCLUDE以外の行は、空行でなければ追加
                if (!ramidiInstruction.isEmpty()) {
                    resultLines.add(ramidiInstruction);
                }
            }
        }
        return resultLines;
    }
}