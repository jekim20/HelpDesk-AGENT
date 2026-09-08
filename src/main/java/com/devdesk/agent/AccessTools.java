package com.devdesk.agent;

import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AccessTools {

    public static final Set<String> SUPPORTED_RESOURCES = Set.of("VPN", "DEV_DB", "PROD_DB");
    private static final Logger log = LoggerFactory.getLogger(AccessTools.class);

    private final AccessRepository accessRepository;
    private final ToolInvocationTracker invocationTracker;

    public AccessTools(AccessRepository accessRepository, ToolInvocationTracker invocationTracker) {
        this.accessRepository = accessRepository;
        this.invocationTracker = invocationTracker;
    }

    @Tool(description = "현재 실행 사용자의 VPN, DEV_DB 또는 PROD_DB 접근 권한 상태를 조회한다.")
    public String getAccessStatus(
            @ToolParam(description = "조회할 리소스: VPN, DEV_DB, PROD_DB 중 하나") String resource,
            ToolContext context) {
        String normalized = normalize(resource);
        if (!SUPPORTED_RESOURCES.contains(normalized)) {
            invocationTracker.markFailed();
            return "지원하지 않는 리소스입니다. VPN, DEV_DB, PROD_DB만 조회할 수 있습니다.";
        }
        try {
            String userId = currentUser(context);
            String status = accessRepository.findStatus(userId, normalized).orElse("NOT_REQUESTED");
            return "%s 권한 상태는 %s입니다.".formatted(normalized, status);
        } catch (RuntimeException error) {
            invocationTracker.markFailed();
            log.warn("권한 저장소 조회 실패 resource={}", normalized);
            return "현재 권한 정보를 조회할 수 없습니다. 잠시 후 다시 확인해 주세요.";
        }
    }

    static String normalize(String resource) {
        return resource == null ? "" : resource.trim().toUpperCase(Locale.ROOT);
    }

    static String currentUser(ToolContext context) {
        Object userId = context == null ? null : context.getContext().get("userId");
        if (userId == null) {
            throw new IllegalStateException("ToolContext에 userId가 없습니다.");
        }
        return userId.toString();
    }
}
