package vn.essvn.erpcafe.identity.web;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.common.web.PageResponse;
import vn.essvn.erpcafe.identity.application.AuthEventLog;
import vn.essvn.erpcafe.identity.web.AuthDtos.AuthEventResponse;

/**
 * Class: AuthEventController
 * Purpose: Serves the caller's company's authentication audit trail.
 */
@RestController
@RequestMapping(ApiVersions.V1 + "/auth-events")
public class AuthEventController {

    private final AuthEventLog events;

    public AuthEventController(AuthEventLog events) {
        this.events = events;
    }

    /** Newest first; {@code userId} narrows it to one user. The page's sort is ignored. */
    @GetMapping
    @PreAuthorize("hasAuthority('user:read')")
    public PageResponse<AuthEventResponse> list(@RequestParam(required = false) UUID userId, Pageable pageable) {
        return PageResponse.from(events.page(userId, pageable).map(AuthEventResponse::of));
    }
}
