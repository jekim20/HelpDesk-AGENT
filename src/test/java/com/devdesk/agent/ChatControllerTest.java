package com.devdesk.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChatControllerTest {

    @Test
    void 빈_질문은_HTTP_400으로_거부한다() {
        HelpDeskService service = mock(HelpDeskService.class);
        ChatController controller = new ChatController(
                service, mock(IngestService.class), mock(TicketRepository.class));

        var response = controller.chat(new ChatController.ChatRequest("", "session", "user1", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().answer()).contains("질문");
        assertThat(response.getBody().toolUsed()).isFalse();
        verifyNoInteractions(service);
    }
}
