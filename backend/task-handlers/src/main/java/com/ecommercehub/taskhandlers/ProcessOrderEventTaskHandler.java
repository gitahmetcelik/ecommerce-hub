package com.ecommercehub.taskhandlers;

import com.ecommercehub.domain.order.OrderEventPayload;
import com.ecommercehub.domain.order.OrderProcessingService;
import com.gorevplatformu.motorcekirdek.GorevBaglami;
import com.gorevplatformu.motorcekirdek.GorevHandler;
import com.gorevplatformu.motorcekirdek.GorevTipi;

/**
 * The engine-side counterpart of the work_batch row IngestService writes (plan
 * Faz 2). Handlers are thin (plan §2's "handler'lar iş mantığı taşımaz" rule) — all
 * of it lives in OrderProcessingService, which is what the ArchUnit rule from Faz 0c
 * (only com.ecommercehub.dispatcher may call the engine) is really protecting: this
 * class is the other direction, the engine calling INTO the domain, not out to it.
 *
 * <p>Throwing here is exactly plan §6's ERTELENDİ — the engine's own retry/backoff
 * (maxDeneme) is the deferral mechanism, not a bespoke second queue.
 */
@GorevTipi(value = "process-order-event", maxDeneme = 8, timeoutSaniye = 30)
public class ProcessOrderEventTaskHandler implements GorevHandler<OrderEventPayload> {

    private final OrderProcessingService orderProcessingService;

    public ProcessOrderEventTaskHandler(OrderProcessingService orderProcessingService) {
        this.orderProcessingService = orderProcessingService;
    }

    @Override
    public Class<OrderEventPayload> payloadTipi() {
        return OrderEventPayload.class;
    }

    @Override
    public Object calistir(OrderEventPayload payload, GorevBaglami baglami) throws Exception {
        orderProcessingService.process(payload);
        return null;
    }
}
