package com.devdesk.agent;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class PromptInjectionGuard {

    public static final String BLOCK_RESPONSE =
            "요청에 포함된 일부 지시는 서비스 정책상 처리할 수 없습니다. "
                    + "HelpDesk와 관련된 일반적인 질문으로 다시 요청해 주세요.";

    private static final List<Pattern> RULES = List.of(
            Pattern.compile("(?iu)\\b(?:ignore|disregard)\\s+(?:(?:all|the)\\s+)?previous\\s+instructions\\b"),
            Pattern.compile("(?iu)(?:이전|기존)(?:의|에)?\\s*(?:모든\\s*)?(?:지시|규칙)(?:를|을)?\\s*(?:모두\\s*)?무시"),
            Pattern.compile("(?iu)\\b(?:reveal|show|print|output)\\s+(?:(?:your|the)\\s+)?(?:system\\s+(?:prompt|instructions)|internal\\s+instructions)\\b"),
            Pattern.compile("(?iu)(?:시스템\\s*프롬프트|내부\\s*(?:지침|지시))(?:를|을|의\\s*내용)?\\s*(?:그대로\\s*)?(?:출력|보여|알려)"),
            Pattern.compile("(?iu)(?:모든|전부|모두)\\s*(?:내부\\s*)?(?:정보|문서)(?:를|의\\s*내용)?\\s*(?:전부\\s*)?(?:출력|보여|알려)"),
            Pattern.compile("(?iu)(?:검색\\s*가능한|내가\\s*볼\\s*수\\s*있는)\\s*(?:모든\\s*)?내부\\s*(?:문서|정보).*(?:전부\\s*)?(?:출력|보여|알려)"),
            Pattern.compile("(?iu)\\b(?:show|print|reveal)\\s+(?:me\\s+)?all\\s+internal\\s+documents\\b")
    );

    public boolean isSuspicious(String prompt) {
        return prompt != null && RULES.stream().anyMatch(rule -> rule.matcher(prompt).find());
    }
}
