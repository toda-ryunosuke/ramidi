package com.rtoda3.ramidi.support;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * 多言語対応メッセージ解決用ユーティリティ
 */
@Component
@RequiredArgsConstructor
public class MessageResolver {

    private final MessageSource messageSource;

    /**
     * デフォルトロケールを使用してメッセージを取得する
     */
    public String getMessage(String code) {
        return messageSource.getMessage(code, null, Locale.getDefault());
    }

    /**
     * デフォルトロケールとパラメータを使用してメッセージを取得する
     */
    public String getMessage(String code, Object... args) {
        return messageSource.getMessage(code, args, Locale.getDefault());
    }
}