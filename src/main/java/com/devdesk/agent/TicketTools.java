package com.devdesk.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.devdesk.agent.TicketRepository.Ticket;
import com.devdesk.agent.TicketRepository.TicketType;

@Component
public class TicketTools {

    private static final Logger log = LoggerFactory.getLogger(TicketTools.class);
    private final TicketRepository ticketRepository;
    private final ToolInvocationTracker invocationTracker;

    public TicketTools(TicketRepository ticketRepository, ToolInvocationTracker invocationTracker) {
        this.ticketRepository = ticketRepository;
        this.invocationTracker = invocationTracker;
    }

    @Tool(description = "접근 권한, 장애 또는 계정 지원 요청을 PENDING 티켓으로 접수한다. 실제 권한 부여나 장애 해결은 수행하지 않는다.")
    public String createTicket(
            @ToolParam(description = "티켓 종류: ACCESS_REQUEST, INCIDENT, ACCOUNT_SUPPORT") TicketType type,
            @ToolParam(description = "관련 리소스") String resource,
            @ToolParam(description = "요청 사유") String reason,
            ToolContext context) {
        try {
            String userId = AccessTools.currentUser(context);
            Ticket ticket = ticketRepository.create(type, resource, reason, userId);
            return "%s 요청을 %s 티켓으로 접수했습니다. 상태는 PENDING이며 담당자 승인 또는 확인 후 처리됩니다."
                    .formatted(resource, ticket.id());
        } catch (RuntimeException error) {
            invocationTracker.markFailed();
            log.warn("티켓 저장소 접수 실패 type={} resource={}", type, resource);
            return "현재 티켓 시스템에 연결할 수 없어 요청을 접수하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        }
    }
}
