package com.rtoda3.ramidi;

import com.rtoda3.ramidi.core.RamidiException;
import com.rtoda3.ramidi.support.MessageResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "soundfont")
@Data
@RequiredArgsConstructor
public class SoundFontProperties {

    private String directory = "./soundfonts/"; // デフォルト値
    private Map<String, String> aliases = new HashMap<>(Map.of(
        "GeneralUser", "GeneralUser.sf2",
        "FluidR3_GM", "FluidR3_GM.sf2"
    ));

    private final MessageResolver messageResolver;

    /**
     * エイリアス名から実際の.sf2ファイルの絶対パスを生成して返す
     */
    public String resolvePath(String aliasOrFilename) {
        // エイリアスに登録されていればそのファイル名、なければそのままファイル名として扱う
        var filename = aliases.getOrDefault(aliasOrFilename, aliasOrFilename + ".sf2");
        var sfPath = Path.of(directory, filename);

        // ファイルが存在すればそのパスを返す
        if (Files.exists(sfPath)) {
            return sfPath.toAbsolutePath().toString();
        }

        var msg = messageResolver.getMessage("error.soundfont.notfound") + filename;
        throw new RamidiException(msg);
    }
}