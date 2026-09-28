package com.github.lisama569.usagebillingenginejava.web;

import com.github.lisama569.usagebillingenginejava.domain.BillingException;
import com.github.lisama569.usagebillingenginejava.domain.ErrorCode;
import com.github.lisama569.usagebillingenginejava.service.EventService;
import com.github.lisama569.usagebillingenginejava.web.dto.ApiDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 用量事件：写入 / 单条查询 / 按账期查询。 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestHeader(value = "X-Operator-Role", defaultValue = "USER") String role,
            @RequestBody ApiDtos.IngestEventRequest req) {
        if (req == null || req.quantity() == null) {
            throw new BillingException(ErrorCode.VALIDATION_FAILED, "quantity is required");
        }
        EventService.IngestResult result = eventService.ingest(req.eventId(), req.accountId(),
                req.metric(), req.quantity(), req.eventTime(), RoleHeader.parse(role));
        return ResponseEntity.ok(Map.of(
                "duplicate", result.duplicate(),
                "period", result.period(),
                "event", result.event()));
    }

    @GetMapping("/{eventId}")
    public Object get(@PathVariable String eventId) {
        return eventService.get(eventId);
    }

    @GetMapping
    public Object list(@RequestParam String accountId, @RequestParam String period) {
        return eventService.list(accountId, period);
    }
}
