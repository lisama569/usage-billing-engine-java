package com.github.lisama569.usagebillingenginejava.api;

import com.github.lisama569.usagebillingenginejava.api.dto.UsageEventRequest;
import com.github.lisama569.usagebillingenginejava.api.dto.UsageEventView;
import com.github.lisama569.usagebillingenginejava.api.dto.WriteResult;
import com.github.lisama569.usagebillingenginejava.service.EventIngestService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 普通用量写入与查询（无需特殊权限；关账后写入被拒绝）。 */
@RestController
@RequestMapping("/api/events")
public class UsageEventController {

    private final EventIngestService ingestService;

    public UsageEventController(EventIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping
    public WriteResult record(@Valid @RequestBody UsageEventRequest request) {
        return ingestService.record(request);
    }

    @GetMapping
    public List<UsageEventView> query(@RequestParam String accountId, @RequestParam String period) {
        return ingestService.query(accountId, period);
    }
}
