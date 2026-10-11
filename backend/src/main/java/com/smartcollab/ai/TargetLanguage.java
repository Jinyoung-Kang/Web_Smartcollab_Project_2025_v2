package com.smartcollab.ai;

import com.smartcollab.global.error.ApiException;

import java.util.Locale;

/**
 * 번역 대상 언어. Claude 에는 언어 이름을, DeepL 에는 DeepL 의 언어 코드를 넘깁니다 [AI-01].
 * Claude 에는 그 언어로 쓴 이름도 함께 줍니다 — 한국어 CSV 를 일본어로 옮기다 중국어 간체로 시작해 고쳐 쓰는 일이 실측에서 있었습니다 [AI-07].
 */
public enum TargetLanguage {
    EN("English", "English", "EN-US"),
    KO("Korean", "한국어", "KO"),
    JA("Japanese", "日本語", "JA"),
    ZH("Simplified Chinese", "简体中文", "ZH-HANS");

    private final String englishName;
    private final String nativeName;
    private final String deeplCode;

    TargetLanguage(String englishName, String nativeName, String deeplCode) {
        this.englishName = englishName;
        this.nativeName = nativeName;
        this.deeplCode = deeplCode;
    }

    /** 프롬프트에 쓰는 이름 — 영어 이름과 그 언어로 쓴 이름 (예: Japanese (日本語)) */
    public String promptName() {
        return englishName.equals(nativeName) ? englishName : englishName + " (" + nativeName + ")";
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
