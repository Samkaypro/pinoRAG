package io.pinoRAG.query;

import io.pinoRAG.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/v1/query")
public class QueryController {

    private final QueryService service;
    private final QueryProperties props;
    private final TenantContext tenant;

    public QueryController(QueryService service, QueryProperties props, TenantContext tenant) {
        this.service = service;
        this.props = props;
        this.tenant = tenant;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter query(@Valid @RequestBody QueryRequest request) {
        // Capture the verified caller HERE, on the request thread, while
        // RequestContextHolder still has attributes set. The virtual thread
        // below cannot read request-scoped beans because they live in a
        // ThreadLocal we have not propagated.
        Long tenantId = tenant.requireTenantId();
        Long apiKeyId = tenant.apiKeyId();
        String subject = tenant.subject();
        String[] groups = tenant.groups();

        SseEmitter emitter = new SseEmitter(props.sseTimeoutMillis());
        Thread.ofVirtual().name("query-" + System.nanoTime()).start(() -> {
            try {
                service.run(request, tenantId, apiKeyId, subject, groups, emitter);
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }
}
