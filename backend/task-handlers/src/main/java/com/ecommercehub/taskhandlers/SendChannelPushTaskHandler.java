package com.ecommercehub.taskhandlers;

import com.ecommercehub.domain.push.ChannelPushWindow;
import com.ecommercehub.domain.push.ChannelPushWindowSender;
import com.gorevplatformu.motorcekirdek.GorevBaglami;
import com.gorevplatformu.motorcekirdek.GorevHandler;
import com.gorevplatformu.motorcekirdek.GorevTipi;

/**
 * Plan §4.2's {@code push-gonder} task: drains one send window for one channel.
 *
 * <p>Thin by the same rule as every other handler — all the work lives behind
 * {@link ChannelPushWindowSender}. Note that {@code @GorevTipi} is itself a
 * {@code @Component} stereotype, so this class is picked up by component scan and its
 * bean name is the task type; there is no separate registration step.
 */
@GorevTipi(value = "push-send", maxDeneme = 5, timeoutSaniye = 60)
public class SendChannelPushTaskHandler implements GorevHandler<ChannelPushWindow> {

    private final ChannelPushWindowSender sender;

    public SendChannelPushTaskHandler(ChannelPushWindowSender sender) {
        this.sender = sender;
    }

    @Override
    public Class<ChannelPushWindow> payloadTipi() {
        return ChannelPushWindow.class;
    }

    @Override
    public Object calistir(ChannelPushWindow payload, GorevBaglami baglami) {
        return sender.sendWindow(payload.organizationId(), payload.channelConnectionId());
    }
}
