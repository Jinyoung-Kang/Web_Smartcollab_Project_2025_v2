package com.smartcollab.ai;

import com.smartcollab.global.error.ApiException;

import java.util.Locale;

/**
 * 번역 대상 언어. Claude 에는 언어 이름을, DeepL 에는 DeepL 의 언어 코드를 넘깁니다 [AI-01].
 */
public enum TargetLanguage {
    EN("English", "EN-US"),
    KO("Korean", "KO"),
    JA("Japanese", "JA"),
    ZH("Simplified Chinese", "ZH-HANS");

    private final String englishName;
    private final String deeplCode;

    TargetLanguage(String englishName, String deeplCode) {
        this.englishName = englishName;
        this.deeplCode = deeplCode;
    }

    public String englishName() {
        return englishName;
    }

    public String deeplCode() {
        return deeplCode;
    }

    public static TargetLanguage parse(String target) {
        if (target != null) {
            try {
                return valueOf(target.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 아래에서 400
            }
        }
        throw ApiException.badRequest("target 은 EN·KO·JA·ZH 중 하나입니다.");
    }
}
