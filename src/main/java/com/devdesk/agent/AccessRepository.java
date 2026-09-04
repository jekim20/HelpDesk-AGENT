package com.devdesk.agent;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

/** 실제 IAM 대신 사용하는 최소 인메모리 접근 상태 저장소. */
@Repository
public class AccessRepository {

    private static final Map<String, String> STATUSES = Map.of(
            key("user1", "VPN"), "APPROVED",
            key("user1", "DEV_DB"), "APPROVED",
            key("user2", "VPN"), "APPROVED",
            key("user2", "PROD_DB"), "APPROVED");

    public Optional<String> findStatus(String userId, String resource) {
        return Optional.ofNullable(STATUSES.get(key(userId, resource)));
    }

    private static String key(String userId, String resource) {
        return userId + ":" + resource;
    }
}
