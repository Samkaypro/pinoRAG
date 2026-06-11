package io.pinoRAG.query;

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

    public QueryController(QueryService service, QueryProperties props) {
        this.service = service;
        this.props = props;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter query(@Valid @RequestBody QueryRequest request) {
        SseEmitter emitter = new SseEmitter(props.sseTimeoutMillis());
        // Run on a virtual thread so the request thread returns immediately;
        // this gets the response headers + first event onto the wire fast.
        Thread.ofVirtual().name("query-" + System.nanoTime()).start(() -> {
            try {
                service.run(request, emitter);
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }
}
