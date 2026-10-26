package io.github.emanuelluis.vaccine.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.emanuelluis.vaccine.region.RegionFilter;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;
import tools.jackson.databind.json.JsonMapper;

/** {@code job_event} notifications reach data-admin subscribers only, as {@code job} events. */
class LiveEventHubJobTest {

    private static final String PAYLOAD =
            "{\"jobId\":\"00000000-0000-0000-0000-000000000001\",\"kind\":\"reconcile\",\"status\":\"running\",\"seq\":3,\"level\":\"info\",\"message\":\"ok\"}";

    private final LiveEventHub hub = new LiveEventHub(JsonMapper.builder().build());

    @AfterEach
    void stop() {
        hub.stop();
    }

    @Test
    void onlyDataAdminSubscribersReceiveJobEvents() throws IOException {
        var admin = mock(SseEmitter.class);
        var reader = mock(SseEmitter.class);
        hub.register(admin, RegionFilter.BR, true);
        hub.register(reader, RegionFilter.BR, false);

        hub.onNotification("job_event", PAYLOAD);

        var sent = ArgumentCaptor.forClass(SseEventBuilder.class);
        verify(admin).send(sent.capture());
        String wire = sent.getValue().build().stream()
                .map(d -> String.valueOf(d.getData()))
                .reduce("", String::concat);
        assertThat(wire).contains("event:job").contains("\"jobId\":\"00000000-0000-0000-0000-000000000001\"");
        assertThat(wire).contains("\"seq\":3").contains("\"status\":\"running\"");
        verify(reader, never()).send(any(SseEventBuilder.class));
    }

    @Test
    void aMalformedPayloadIsIgnored() throws IOException {
        var admin = mock(SseEmitter.class);
        hub.register(admin, RegionFilter.BR, true);

        hub.onNotification("job_event", "not json");

        verify(admin, never()).send(any(SseEventBuilder.class));
    }
}
