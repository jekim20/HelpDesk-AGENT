package com.devdesk.agent;

import org.springframework.stereotype.Component;

/** 한 API 요청 안에서 Tool이 실제 호출되었는지만 추적한다. */
@Component
public class ToolInvocationTracker {

    private final ThreadLocal<Boolean> invoked = ThreadLocal.withInitial(() -> false);

    public void begin() {
        invoked.set(false);
    }

    public void markInvoked() {
        invoked.set(true);
    }

    public boolean wasInvoked() {
        return invoked.get();
    }

    public void clear() {
        invoked.remove();
    }
}
