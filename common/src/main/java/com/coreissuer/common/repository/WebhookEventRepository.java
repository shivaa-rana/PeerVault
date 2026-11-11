package com.coreissuer.common.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import com.coreissuer.common.domain.WebhookEventRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface WebhookEventRepository extends JpaRepository<WebhookEventRecord, String> {
    @Modifying
    @Query(value = """
            insert into webhook_event (provider_event_id, event_type, processed_at)
            values (:eventId, :eventType, now())
            on conflict (provider_event_id) do nothing
            """, nativeQuery = true)
    int claim(@Param("eventId") String eventId, @Param("eventType") String eventType);
}
